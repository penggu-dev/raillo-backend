package com.sudo.raillo.payment.adapter.integration;

import java.util.List;
import org.springframework.stereotype.Component;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.required.ReservationReader;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReservationAdapter implements ReservationReader {
	private final ReservationService reservationService;

	@Override
	public List<Reservation> getReservations(List<String> reservationIds, String memberNo) {
		return reservationService.getReservations(reservationIds, memberNo);
	}
}
