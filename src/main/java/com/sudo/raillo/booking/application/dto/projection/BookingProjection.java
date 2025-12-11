package com.sudo.raillo.booking.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import java.time.LocalDate;
import java.time.LocalTime;
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
		LocalDate operationDate
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
	}
}
