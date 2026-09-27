package com.sudo.raillo.booking.application.dto;

import java.time.LocalDate;
import java.util.List;

/** 결제가 확정된 예약 하나를 예매 점유로 바꾸는 요청. */
public record BookingConversionRequest(
	String reservationId,
	String memberNo,
	long trainScheduleId,
	LocalDate operationDate,
	long bookingId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
	public record SeatCar(long seatId, long trainCarId) {
	}
}
