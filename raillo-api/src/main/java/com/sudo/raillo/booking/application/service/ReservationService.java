package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyResult;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.exception.TrainError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
			// 응답 타임아웃처럼 스크립트가 이미 저장했을 수 있으니, 저장됐으면 성공으로 보고 인덱스를 남긴다
			if (!isStored(reservation)) {
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

	private static long toTtlSeconds(Duration ttl) {
		long seconds = (ttl.toMillis() + 999) / 1000;
		return Math.max(1L, seconds);
	}

	private boolean isStored(Reservation reservation) {
		try {
			return reservationRedisRepository.exists(reservation.trainScheduleId(), reservation.reservationId());
		} catch (RuntimeException e) {
			log.warn("[예약 저장 여부 확인 실패] reservationId={}, error={}", reservation.reservationId(), e.getMessage());
			return false;
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
