package com.sudo.raillo.train.cache;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sudo.raillo.train.domain.status.OperationStatus;

/**
 * key: {@code {schedule:id}:info}
 */
public record ScheduleInfoCacheValue(
	long trainScheduleId,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
	LocalDate operationDate,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
	LocalTime departureTime,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
	LocalTime arrivalTime,

	OperationStatus operationStatus,
	int delayMinutes,
	long trainId,
	int trainNumber,
	String trainName,
	long departureStationId,
	long arrivalStationId
) {
}
