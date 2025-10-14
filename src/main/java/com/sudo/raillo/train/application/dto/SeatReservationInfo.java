package com.sudo.raillo.train.application.dto;

import com.sudo.raillo.train.domain.type.CarType;

public record SeatReservationInfo(
	Long seatId,
	CarType carType,
	Long departureStationId,
	Long arrivalStationId
) {
}
