package com.sudo.raillo.booking.application.dto;

import java.time.LocalTime;

public record StopInfo(
	String stationName,
	LocalTime departureTime,
	LocalTime arrivalTime
) {
}
