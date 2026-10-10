package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.BookingSeatReleaseRequest;
import com.sudo.raillo.booking.application.service.BookedSeatService;
import com.sudo.raillo.payment.application.BookingSeatReleasePayload;
import com.sudo.raillo.payment.application.required.BookedSeatReleaser;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BookedSeatReleaserAdapter implements BookedSeatReleaser {

	private final BookedSeatService bookedSeatService;

	@Override
	public void release(BookingSeatReleasePayload payload) {
		bookedSeatService.releaseBookedSeats(new BookingSeatReleaseRequest(
			payload.trainScheduleId(),
			payload.bookingId(),
			payload.departureStopOrder(),
			payload.arrivalStopOrder(),
			payload.seats().stream()
				.map(seat -> new BookingSeatReleaseRequest.SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		));
	}
}
