package com.sudo.raillo.booking.domain;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

public record ReservationStop(
	long stopId,
	int stopOrder,
	long stationId,
	String stationName,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
	LocalTime time
) {
}
