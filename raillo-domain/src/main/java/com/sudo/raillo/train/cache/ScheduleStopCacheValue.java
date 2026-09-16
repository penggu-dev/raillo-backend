package com.sudo.raillo.train.cache;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * key: {@code {schedule:id}:stops}
 *
 * @param arrivalTime 기점은 {@code null}
 * @param departureTime 종점은 {@code null}
 */
public record ScheduleStopCacheValue(
	long stopId,
	int stopOrder,
	long stationId,
	String stationName,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
	LocalTime arrivalTime,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
	LocalTime departureTime
) {
}
