package com.sudo.raillo.booking.application.dto;

import java.time.LocalDate;

public record TrainScheduleInfo(
	String trainNumber,
	String trainName,
	LocalDate operationDate
) {
}
