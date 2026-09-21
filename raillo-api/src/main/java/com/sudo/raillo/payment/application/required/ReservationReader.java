package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.Reservation;

/**
 * Reservation 조회 required port.
 */
public interface ReservationReader {

	List<Reservation> getReservations(List<String> reservationIds, String memberNo);
}
