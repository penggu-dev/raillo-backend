package com.sudo.raillo.payment.adapter.integration;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.BookingCreator;
import com.sudo.raillo.payment.application.result.ConfirmedBookingResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingCreatorAdapter implements BookingCreator {

	private final BookingService bookingService;

	@Override
	public List<ConfirmedBookingResult> createBookingFromOrder(Order order) {
		return bookingService.createBookingFromOrder(order).stream()
			.map(info -> new ConfirmedBookingResult(info.reservationId(), info.bookingId()))
			.toList();
	}
}
