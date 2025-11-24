package com.sudo.raillo.booking.application.validator;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReservationValidator {

	private final ScheduleStopRepository scheduleStopRepository;

	/**
	 * 출발지, 도착지 순서 검증
	 * @param reservation
	 */
	public void validateStopSequence(Reservation reservation) {
		ScheduleStop departureStop = reservation.getDepartureStop();
		ScheduleStop arrivalStop = reservation.getArrivalStop();
		validateStopOrder(departureStop.getStopOrder(), arrivalStop.getStopOrder());
	}

	/**
	 * 출발지, 도착지 순서 검증 (ID 기반 - 임시 예약용)
	 */
	public void validateStopSequence(
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId
	) {
		ScheduleStop departureStop = scheduleStopRepository
			.findByTrainScheduleIdAndStationId(trainScheduleId, departureStationId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));

		ScheduleStop arrivalStop = scheduleStopRepository
			.findByTrainScheduleIdAndStationId(trainScheduleId, arrivalStationId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));

		validateStopOrder(departureStop.getStopOrder(), arrivalStop.getStopOrder());
	}

	/**
	 * stopOrder 검증 공통 로직
	 */
	private void validateStopOrder(int departureOrder, int arrivalOrder) {
		if (departureOrder >= arrivalOrder) {
			throw new BusinessException(BookingError.INVALID_STOP_SEQUENCE);
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

	/**
	 * 요청된 승객 수와 선택한 좌석 수의 일치 여부를 검증
	 * */
	public void validatePassengerSeatCount(List<PassengerSummary> passengers, List<Long> seatIds) {
		// 요청 승객 수와 선택한 좌석 수를 비교하여 좌석 수가 승객 수보다 많으면 오류 발생
		int passengersCnt = passengers.stream()
			.mapToInt(PassengerSummary::getCount)
			.sum();
		if (passengersCnt != seatIds.size()) {
			throw new BusinessException(BookingError.RESERVATION_CREATE_SEATS_INVALID);
		}
	}
}
