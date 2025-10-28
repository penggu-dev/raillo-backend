package com.sudo.raillo.booking.application.dto;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.Seat;

public record SeatPassengerPair(
	Seat seat,
	PassengerType passengerType
) {
}
