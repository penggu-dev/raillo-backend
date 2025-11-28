package com.sudo.raillo.booking.application.dto.projection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.querydsl.core.annotations.QueryProjection;

import lombok.Getter;

@Getter
public class BookingProjection {

	private final Long bookingId;
	private final String bookingCode;
	private final int trainNumber;
	private final String trainName;
	private final String departureStationName;
	private final String arrivalStationName;
	private final LocalTime departureTime;
	private final LocalTime arrivalTime;
	private final LocalDate operationDate;
	private final LocalDateTime expiresAt;
	private final int fare;

	@QueryProjection
	public BookingProjection(
		Long bookingId,
		String bookingCode,
		int trainNumber,
		String trainName,
		String departureStationName,
		String arrivalStationName,
		LocalTime departureTime,
		LocalTime arrivalTime,
		LocalDate operationDate,
		LocalDateTime expiresAt,
		int fare
	) {
		this.bookingId = bookingId;
		this.bookingCode = bookingCode;
		this.trainNumber = trainNumber;
		this.trainName = trainName;
		this.departureStationName = departureStationName;
		this.arrivalStationName = arrivalStationName;
		this.departureTime = departureTime;
		this.arrivalTime = arrivalTime;
		this.operationDate = operationDate;
		this.expiresAt = expiresAt;
		this.fare = fare;
	}
}
