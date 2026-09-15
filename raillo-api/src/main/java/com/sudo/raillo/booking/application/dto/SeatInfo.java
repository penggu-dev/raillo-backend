package com.sudo.raillo.booking.application.dto;

import com.sudo.raillo.train.domain.type.CarType;

public record SeatInfo(
	int carNumber,
	CarType carType,
	String seatNumber
) {
}
