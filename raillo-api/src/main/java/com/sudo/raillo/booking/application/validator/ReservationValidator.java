package com.sudo.raillo.booking.application.validator;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReservationValidator {

	public void validateOperating(ScheduleInfoCacheValue schedule) {
		if (schedule.operationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainError.TRAIN_OPERATION_CANCELLED);
		}
	}

	public void validateDifferentStations(long departureStationId, long arrivalStationId) {
		if (departureStationId == arrivalStationId) {
			throw new BusinessException(TrainError.INVALID_ROUTE);
		}
	}

	/**
	 * 출발 정차역이 도착 정차역보다 앞서야 한다. 같은 역이면 점유할 구간이 없으므로 거부한다.
	 */
	public void validateStopSequence(ScheduleStopCacheValue departureStop, ScheduleStopCacheValue arrivalStop) {
		if (departureStop.stopOrder() >= arrivalStop.stopOrder()) {
			throw new BusinessException(TrainError.INVALID_ROUTE);
		}
	}

	/**
	 * 출발 {@link Reservation#BOOKING_CLOSE_BEFORE_DEPARTURE} 전에 예약이 마감된다.
	 */
	public void validateBookingOpen(LocalDateTime departureAt, LocalDateTime now) {
		if (!now.isBefore(Reservation.bookingCloseAt(departureAt))) {
			throw new BusinessException(TrainError.DEPARTURE_TIME_PASSED);
		}
	}

	public void validatePassengerSeatCount(List<PassengerType> passengerTypes, List<Long> seatIds) {
		if (passengerTypes.size() != seatIds.size()) {
			throw new BusinessException(BookingError.BOOKING_CREATE_SEATS_INVALID);
		}
	}

	public void validateDistinctSeats(List<Long> seatIds) {
		Set<Long> distinct = new HashSet<>(seatIds);
		if (distinct.size() != seatIds.size()) {
			log.warn("[좌석 중복 선택] seatIds={}", seatIds);
			throw new BusinessException(BookingError.DUPLICATE_SEAT_IDS);
		}
	}

	/**
	 * 한 예약의 좌석은 모두 같은 객차 타입이어야 한다. 객차가 달라도 타입만 같으면 허용한다.
	 */
	public CarType validateSingleCarType(Collection<SeatCacheValue> seats) {
		Set<CarType> carTypes = new HashSet<>();
		seats.forEach(seat -> carTypes.add(seat.carType()));

		if (carTypes.size() != 1) {
			log.warn("[객차 타입 불일치] carTypes={}", carTypes);
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.iterator().next();
	}

	public void validateReservationIdsPresent(List<String> reservationIds) {
		if (reservationIds == null || reservationIds.isEmpty()) {
			throw new BusinessException(BookingError.RESERVATION_IDS_REQUIRED);
		}
	}

	/**
	 * 요청한 예약이 모두 존재해야 한다. 없으면 TTL 만료이거나 이미 처리된 것이다.
	 */
	public void validateAllReservationsExist(List<String> reservationIds, Map<String, ?> found) {
		List<String> missing = reservationIds.stream()
			.filter(id -> !found.containsKey(id))
			.toList();

		if (!missing.isEmpty()) {
			log.warn("[예약 만료 또는 없음] reservationIds={}", missing);
			throw new BusinessException(BookingError.RESERVATION_EXPIRED);
		}
	}

	public void validateOwner(Reservation reservation, String memberNo) {
		if (!reservation.memberNo().equals(memberNo)) {
			log.error("[예약 소유자 불일치] reservationId={}, owner={}, requester={}",
				reservation.reservationId(), reservation.memberNo(), memberNo);
			throw new BusinessException(BookingError.RESERVATION_ACCESS_DENIED);
		}
	}
}
