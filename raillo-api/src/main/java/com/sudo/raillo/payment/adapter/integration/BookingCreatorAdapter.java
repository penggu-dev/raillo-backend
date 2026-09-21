package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.BookingCreator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BookingCreatorAdapter implements BookingCreator {

	private final BookingService bookingService;

	@Override
	public java.util.List<com.sudo.raillo.payment.application.result.ConfirmedBookingResult> createBookingFromOrder(Order order) {
		return bookingService.createBookingFromOrder(order).stream()
			.map(info -> new com.sudo.raillo.payment.application.result.ConfirmedBookingResult(info.reservationId(), info.bookingId()))
			.toList();
	}
}
