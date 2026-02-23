package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.domain.type.PassengerType;

public record PendingSeatBooking(
	Long seatId,
	PassengerType passengerType
) {
}
