package com.sudo.raillo.booking.application.mapper;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.SeatReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.infrastructure.SeatRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SeatPassengerMapper {

	private final SeatRepository seatRepository;
	private final SeatReservationService seatReservationService;

	public List<Long> mapSeatsToPassengers(
		Reservation reservation,
		List<PassengerSummary> passengers,
		List<Long> seatIds
	) {
		// 요청 승객 수와 선택한 좌석 수를 비교하여 좌석 수가 승객 수보다 많으면 오류 발생
		int passengersCnt = passengers.stream()
			.mapToInt(PassengerSummary::getCount)
			.sum();
		if (passengersCnt != seatIds.size()) {
			throw new BusinessException(BookingError.RESERVATION_CREATE_SEATS_INVALID);
		}

		// 좌석 차례대로 승객 할당
		int idx = 0;
		List<Long> seatReservationIds = new ArrayList<>();
		for (PassengerSummary passenger : passengers) {
			PassengerType passengerType = passenger.getPassengerType();
			int passengerCnt = passenger.getCount();
			for (int i = 0; i < passengerCnt && idx < seatIds.size(); i++, idx++) {
				Seat seat = seatRepository.findById(seatIds.get(idx))
					.orElseThrow(() -> new BusinessException((BookingError.SEAT_NOT_FOUND)));
				SeatReservation seatReservation = seatReservationService
					.reserveNewSeat(reservation, seat, passengerType);
				seatReservationIds.add(seatReservation.getId());
			}
		}

		return seatReservationIds;
	}

}
