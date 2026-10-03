package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.BookingConversionRequest;
import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingOccupancyCommand;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyResult;
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
import org.springframework.stereotype.Service;

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
			result = seatOccupancyRepository.occupy(toOccupyCommand(reservation, ttl));
		} catch (RuntimeException e) {
			// 응답 타임아웃처럼 스크립트가 이미 저장했을 수 있으니, 저장됐으면 성공으로 보고 인덱스를 남긴다.
			// 데이터 오염은 스크립트가 아무것도 쓰지 않았다는 확정 신호이므로 이 구제 대상이 아니다
			if (isCorrupted(e) || !isStored(reservation)) {
				rollbackMemberIndex(memberNo, reservationId);
				throw e;
			}
			log.warn("[좌석 점유 응답 실패 - 저장 확인] reservationId={}, error={}", reservationId, e.getMessage());
			result = SeatOccupancyResult.succeeded();
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
	 * 결제가 확정된 예약의 좌석을 예매 점유로 바꾸고 회원 인덱스를 지운다. 예약 본문은 스크립트가 지운다.
	 *
	 * @return 전환했으면 true, 다른 예약이나 예매와 충돌해 아무것도 쓰지 않았으면 false
	 */
	public boolean convertToBooking(BookingConversionRequest request) {
		SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(new BookingOccupancyCommand(
			request.trainScheduleId(),
			request.reservationId(),
			request.bookingId(),
			TrainCacheKey.expireAtEpochSecond(request.operationDate()),
			request.departureStopOrder(),
			request.arrivalStopOrder(),
			request.seats().stream()
				.map(seat -> new SeatOccupancyCommand.SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		));
		if (result.success()) {
			reservationRedisRepository.removeMemberIndex(request.memberNo(), request.reservationId());
		}
		return result.success();
	}

	/** 좌석은 그대로 두고 예약 본문과 회원 인덱스만 지운다. */
	public void discardReservation(long trainScheduleId, String reservationId, String memberNo) {
		reservationRedisRepository.delete(trainScheduleId, reservationId);
		reservationRedisRepository.removeMemberIndex(memberNo, reservationId);
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
			reservation.seats().stream()
				.map(seat -> new SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		);
	}

	private static SeatReleaseCommand toReleaseCommand(Reservation reservation) {
		return new SeatReleaseCommand(
			reservation.trainScheduleId(),
			reservation.reservationId(),
			reservation.departure().stopOrder(),
			reservation.arrival().stopOrder(),
			reservation.seats().stream()
				.map(seat -> new SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		);
	}

	private static long toTtlSeconds(Duration ttl) {
		long seconds = (ttl.toMillis() + 999) / 1000;
		return Math.max(1L, seconds);
	}

	private static boolean isCorrupted(RuntimeException e) {
		return e instanceof BusinessException business
			&& business.getErrorCode() == BookingError.SEAT_OCCUPANCY_CORRUPTED;
	}

	private boolean isStored(Reservation reservation) {
		try {
			return reservationRedisRepository.exists(reservation.trainScheduleId(), reservation.reservationId());
		} catch (RuntimeException e) {
			log.warn("[예약 저장 여부 확인 실패] reservationId={}, error={}", reservation.reservationId(), e.getMessage());
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
