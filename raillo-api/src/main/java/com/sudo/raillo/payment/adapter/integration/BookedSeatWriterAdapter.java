package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.BookingConversionRequest;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.required.BookedSeatWriter;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BookedSeatWriterAdapter implements BookedSeatWriter {

	private final ReservationService reservationService;

	@Override
	public boolean markBooked(BookingConfirmedPayload.Entry entry) {
		return reservationService.convertToBooking(new BookingConversionRequest(
			entry.reservationId(),
			entry.memberNo(),
			entry.trainScheduleId(),
			entry.operationDate(),
			entry.bookingId(),
			entry.departureStopOrder(),
			entry.arrivalStopOrder(),
			entry.seats().stream()
				.map(seat -> new BookingConversionRequest.SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		));
	}

	@Override
	public void discardReservation(BookingConfirmedPayload.Entry entry) {
		reservationService.discardReservation(entry.trainScheduleId(), entry.reservationId(), entry.memberNo());
	}
}
