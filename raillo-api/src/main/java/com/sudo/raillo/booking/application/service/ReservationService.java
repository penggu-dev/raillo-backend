package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.ReservationReleaseRequest;
import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyResult;
import com.sudo.raillo.booking.infrastructure.SeatHoldCommand;
import com.sudo.raillo.booking.infrastructure.SeatHoldReleaseCommand;
import com.sudo.raillo.booking.infrastructure.SeatReleaseCommand;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.exception.TrainError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

/**
 * `Reservation` 점유({@code R:{reservationId}})와 Reservation 본문, 회원 인덱스의 생애주기를 다룬다.
 *
 * <p>`Booking` 점유({@code B:})는 {@link BookedSeatService}가 다룬다. Reservation 생성과 취소는 Lua
 * 스크립트 하나가 좌석 점유와 Reservation 본문을 함께 쓰므로 쪼갤 수 없고 여기 남는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

	private static final Duration MIN_TTL = Duration.ofSeconds(1);

	private final ReservationRedisRepository reservationRedisRepository;
	private final SeatOccupancyRepository seatOccupancyRepository;
	private final RedisJsonConverter redisJsonConverter;
	private final ReservationValidator reservationValidator;

	@Value("${redis.ttl.reservation}")
	private Duration defaultTtl;

	/**
	 * 예약 TTL 계산. 출발 5분 전부터는 예약이 마감되어 만들 수 없다.
	 *
	 * @throws BusinessException 예약이 마감된 출발 시각일 때
	 */
	public Duration calculateTtl(LocalDateTime departureAt, LocalDateTime now) {
		Duration untilClose = Duration.between(now, Reservation.bookingCloseAt(departureAt));
		if (untilClose.isNegative() || untilClose.isZero()) {
			log.warn("[예약 마감] departureAt={}, now={}", departureAt, now);
			throw new BusinessException(TrainError.DEPARTURE_TIME_PASSED);
		}

		Duration ttl = untilClose.compareTo(defaultTtl) < 0 ? untilClose : defaultTtl;
		return ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
	}

	/**
	 * 회원 인덱스에 먼저 등록한 뒤 좌석을 원자적으로 점유
	 *
	 * @throws BusinessException 다른 점유와 충돌했거나 스크립트가 실패했을 때
	 */
	public void reserve(Reservation reservation, Duration ttl) {
		String memberNo = reservation.memberNo();
		String reservationId = reservation.reservationId();

		reservationRedisRepository.indexForMember(memberNo, reservationId, reservation.trainScheduleId(), ttl);

		SeatOccupancyResult result;
		try {
			result = occupyWithRetry(reservation, ttl);
		} catch (RuntimeException e) {
			// 앞선 실행이 저장했는지 모르므로 자기 점유와 본문까지 되돌린다. 자기 값인 field만 지우므로 다른 점유는 남는다.
			// 해제가 실패하면 인덱스를 남긴다. 지우면 점유가 남았을 때 회원이 그 예약을 찾아 취소할 수 없다
			if (releaseQuietly(reservation)) {
				rollbackMemberIndex(memberNo, reservationId);
			}
			throw e;
		}

		if (!result.success()) {
			rollbackMemberIndex(memberNo, reservationId);
			throw new BusinessException(result.isConflictWithBooking()
				? BookingError.SEAT_CONFLICT_WITH_BOOKING
				: BookingError.SEAT_CONFLICT_WITH_RESERVATION);
		}

		log.info("[예약 생성] reservationId={}, memberNo={}, trainScheduleId={}, seatCount={}, ttl={}",
			reservationId, memberNo, reservation.trainScheduleId(), reservation.seats().size(), ttl);
	}

	public List<Reservation> getReservations(List<String> reservationIds, String memberNo) {
		reservationValidator.validateReservationIdsPresent(reservationIds);

		Map<String, Long> scheduleIds = reservationRedisRepository.findScheduleIds(memberNo, reservationIds);
		reservationValidator.validateAllReservationsExist(reservationIds, scheduleIds);

		Map<String, Reservation> found = reservationRedisRepository.findAll(scheduleIds);
		reservationValidator.validateAllReservationsExist(reservationIds, found);

		List<Reservation> reservations = reservationIds.stream().map(found::get).toList();
		reservations.forEach(reservation -> reservationValidator.validateOwner(reservation, memberNo));
		return reservations;
	}

	public List<Reservation> getMyReservations(String memberNo) {
		Map<String, Long> scheduleIds = reservationRedisRepository.findScheduleIds(memberNo);
		return reservationRedisRepository.findAll(scheduleIds).values().stream()
			.filter(reservation -> reservation.memberNo().equals(memberNo))
			.sorted(Comparator.comparing(Reservation::createdAt).thenComparing(Reservation::reservationId))
			.toList();
	}

	/**
	 * 내 예약을 지우고 좌석 점유를 해제한다. 이미 만료됐거나 없는 예약은 성공으로 본다.
	 * 점유 해제를 먼저 하고 회원 인덱스를 나중에 지운다. 반대 순서면 점유가 남은 채 인덱스만 사라져 다시 지울 수 없다.
	 *
	 * @throws BusinessException 다른 회원의 예약이거나 점유 해제에 실패했을 때
	 */
	public void cancel(String reservationId, String memberNo) {
		Optional<Long> scheduleId = reservationRedisRepository.findScheduleId(memberNo, reservationId);
		if (scheduleId.isEmpty()) {
			return;
		}

		Optional<Reservation> reservation = reservationRedisRepository.find(scheduleId.get(), reservationId);
		if (reservation.isPresent()) {
			reservationValidator.validateOwner(reservation.get(), memberNo);
			seatOccupancyRepository.release(toReleaseCommand(reservation.get()));
		}

		removeMemberIndexQuietly(memberNo, reservationId);
		log.info("[예약 삭제] reservationId={}, memberNo={}", reservationId, memberNo);
	}

	/**
	 * 예매가 되지 못한 예약의 좌석 점유와 예약 본문, 회원 인덱스를 되돌린다. 예약 본문은 해제 스크립트가 함께 지운다.
	 *
	 * <p>값이 {@code R:{reservationId}}인 field만 지우므로 다른 예약이나 예매의 점유는 남는다. 결제 중 보호가
	 * 걸렸던 예약은 field에 만료가 없어, 여기서 지우지 않으면 운행일까지 좌석이 묶인다.</p>
	 *
	 * @throws BusinessException 점유 해제 스크립트 실행이 실패했을 때
	 */
	public void releaseReservation(ReservationReleaseRequest request) {
		seatOccupancyRepository.release(new SeatReleaseCommand(
			request.trainScheduleId(), request.reservationId(),
			request.departureStopOrder(), request.arrivalStopOrder(),
			request.seats().stream().map(seat -> new SeatCar(seat.seatId(), seat.trainCarId())).toList()));
		removeMemberIndexQuietly(request.memberNo(), request.reservationId());
	}

	/**
	 * 결제 결과를 모르는 동안 자기 예약의 좌석 field를 붙잡는다. 예약 본문과 회원 인덱스는 원래 TTL을 따른다.
	 *
	 * @return 붙잡았으면 true, 다른 예약이나 예매와 충돌해 아무것도 쓰지 않았으면 false
	 * @throws BusinessException 스크립트 실행이 실패했을 때
	 */
	public boolean holdForPayment(Reservation reservation) {
		return seatOccupancyRepository.hold(new SeatHoldCommand(
			reservation.trainScheduleId(),
			reservation.reservationId(),
			TrainCacheKey.expireAtEpochSecond(reservation.operationDate()),
			reservation.departure().stopOrder(),
			reservation.arrival().stopOrder(),
			toSeatCars(reservation)
		)).success();
	}

	/**
	 * 자기 예약 좌석 field의 만료를 보호 이전으로 되돌린다. 되돌릴 기한이 없으면 스크립트가 자기 field를 지운다.
	 *
	 * @throws BusinessException 스크립트 실행이 실패했을 때
	 */
	public void releasePaymentHold(Reservation reservation) {
		seatOccupancyRepository.releaseHold(new SeatHoldReleaseCommand(
			reservation.trainScheduleId(),
			reservation.reservationId(),
			reservation.departure().stopOrder(),
			reservation.arrival().stopOrder(),
			toSeatCars(reservation)
		));
	}

	private SeatOccupancyCommand toOccupyCommand(Reservation reservation, Duration ttl) {
		return new SeatOccupancyCommand(
			reservation.trainScheduleId(),
			reservation.reservationId(),
			toTtlSeconds(ttl),
			TrainCacheKey.expireAtEpochSecond(reservation.operationDate()),
			redisJsonConverter.toJson(reservation),
			reservation.departure().stopOrder(),
			reservation.arrival().stopOrder(),
			toSeatCars(reservation)
		);
	}

	private static SeatReleaseCommand toReleaseCommand(Reservation reservation) {
		return new SeatReleaseCommand(
			reservation.trainScheduleId(),
			reservation.reservationId(),
			reservation.departure().stopOrder(),
			reservation.arrival().stopOrder(),
			toSeatCars(reservation)
		);
	}

	private static List<SeatCar> toSeatCars(Reservation reservation) {
		return reservation.seats().stream()
			.map(seat -> new SeatCar(seat.seatId(), seat.trainCarId()))
			.toList();
	}

	private static long toTtlSeconds(Duration ttl) {
		long seconds = (ttl.toMillis() + 999) / 1000;
		return Math.max(1L, seconds);
	}

	/**
	 * 응답을 받지 못한 실패만 1회 다시 실행한다.
	 *
	 * <p>스크립트는 같은 예약 ID에 멱등이다. 자기 점유 값은 충돌 검사를 통과하고 본문은 같은 값으로 덮인다.
	 * 그래서 앞선 실행이 저장했든 안 했든, 서버에서 늦게 실행되든 재실행 결과가 최종 결과다.
	 * 재실행은 field와 본문의 TTL을 다시 걸어 실제 만료가 {@code expiresAt}보다 재시도 시간만큼 늦어진다.</p>
	 *
	 * <p>응답을 받은 실패(스크립트 오류, 데이터 오염)는 다시 실행해도 같으므로 재시도하지 않는다.</p>
	 */
	private SeatOccupancyResult occupyWithRetry(Reservation reservation, Duration ttl) {
		SeatOccupancyCommand command = toOccupyCommand(reservation, ttl);
		try {
			return seatOccupancyRepository.occupy(command);
		} catch (RuntimeException e) {
			if (!isResponseLost(e)) {
				throw e;
			}
			log.warn("[좌석 점유 응답 실패 - 재시도] reservationId={}, error={}",
				reservation.reservationId(), e.getCause().getMessage());
			return seatOccupancyRepository.occupy(command);
		}
	}

	/** 명령을 보내지 못했거나 보낸 뒤 응답을 받지 못한 실패. 둘 다 서버의 실행 여부를 확정할 수 없다. */
	private static boolean isResponseLost(RuntimeException e) {
		Throwable cause = e.getCause();
		return cause instanceof QueryTimeoutException || cause instanceof RedisConnectionFailureException;
	}

	/** @return 해제했으면 true. 실패하면 남은 점유와 본문은 TTL로 사라진다 */
	private boolean releaseQuietly(Reservation reservation) {
		try {
			seatOccupancyRepository.release(toReleaseCommand(reservation));
			return true;
		} catch (RuntimeException e) {
			log.error("[좌석 점유 보상 실패 - 회원 인덱스 유지] reservationId={}, memberNo={}, error={}",
				reservation.reservationId(), reservation.memberNo(), e.getMessage());
			return false;
		}
	}

	private void removeMemberIndexQuietly(String memberNo, String reservationId) {
		try {
			reservationRedisRepository.removeMemberIndex(memberNo, reservationId);
		} catch (RuntimeException e) {
			// field TTL로 사라지고 조회는 본문 없는 인덱스를 만료로 처리하므로 실패해도 무해하다
			log.warn("[회원 인덱스 삭제 실패] reservationId={}, memberNo={}, error={}", reservationId, memberNo, e.getMessage());
		}
	}

	private void rollbackMemberIndex(String memberNo, String reservationId) {
		try {
			reservationRedisRepository.removeMemberIndex(memberNo, reservationId);
		} catch (RuntimeException e) {
			log.warn("[회원 인덱스 보상 실패] reservationId={}, memberNo={}, error={}", reservationId, memberNo, e.getMessage());
		}
	}
}
