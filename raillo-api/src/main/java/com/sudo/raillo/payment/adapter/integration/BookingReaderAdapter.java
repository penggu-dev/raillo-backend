package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.payment.application.required.BookingReader;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BookingReaderAdapter implements BookingReader {

	private final BookingService bookingService;

	@Override
	public boolean isBooked(long bookingId) {
		return bookingService.isBooked(bookingId);
	}
}
