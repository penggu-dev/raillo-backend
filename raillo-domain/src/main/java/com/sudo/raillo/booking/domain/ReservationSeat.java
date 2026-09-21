package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;

import com.sudo.raillo.booking.domain.type.PassengerType;

public record ReservationSeat(
	long seatId,
	long trainCarId,
	int carNumber,
	String seatLabel,
	PassengerType passengerType,
	BigDecimal fare
) {
}
