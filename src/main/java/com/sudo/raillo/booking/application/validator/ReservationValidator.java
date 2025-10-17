package com.sudo.raillo.booking.application.validator;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;

@Component
public class ReservationValidator {

	/**
	 * 출발지, 도착지 순서 검증
	 * @param reservation
	 */
	public void validateStopSequence(Reservation reservation) {
		ScheduleStop departureStop = reservation.getDepartureStop();
		ScheduleStop arrivalStop = reservation.getArrivalStop();
		if (departureStop.getStopOrder() > arrivalStop.getStopOrder()) {
			throw new BusinessException(BookingError.TRAIN_NOT_OPERATIONAL);
		}
	}

	/**
	 * 기존 예약들과 충돌 검증 (락이 걸린 상태에서 수행)
	 */
	public void validateConflictWithExistingReservations(
		Reservation newReservation,
		List<SeatReservation> existingReservations
	) {
		int newDepartureOrder = newReservation.getDepartureStop().getStopOrder();
		int newArrivalOrder = newReservation.getArrivalStop().getStopOrder();

		existingReservations.forEach(existingReservation -> {
			int existingDepartureOrder = existingReservation.getReservation().getDepartureStop().getStopOrder();
			int existingArrivalOrder = existingReservation.getReservation().getArrivalStop().getStopOrder();
			if (existingDepartureOrder < newArrivalOrder && existingArrivalOrder > newDepartureOrder) {
				throw new BusinessException(BookingError.SEAT_ALREADY_RESERVED);
			}
		});
	}
}
