package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.required.SeatHolder;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SeatHolderAdapter implements SeatHolder {

	private final ReservationService reservationService;

	@Override
	public boolean hold(Reservation reservation) {
		return reservationService.holdForPayment(reservation);
	}

	@Override
	public void releaseHold(Reservation reservation) {
		reservationService.releasePaymentHold(reservation);
	}
}
