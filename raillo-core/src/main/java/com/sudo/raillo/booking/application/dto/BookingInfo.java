package com.sudo.raillo.booking.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.sudo.raillo.booking.application.dto.projection.BookingProjection;
import com.sudo.raillo.booking.application.dto.projection.TicketProjection;

public record BookingInfo(
	Long bookingId,
	String bookingCode,
	int trainNumber,
	String trainName,
	String departureStationName,
	String arrivalStationName,
	LocalTime departureTime,
	LocalTime arrivalTime,
	LocalDate operationDate,
	List<TicketProjection> tickets
) {

	public static BookingInfo of(
		BookingProjection projection,
		List<TicketProjection> tickets
	) {
		return new BookingInfo(
			projection.getBookingId(),
			projection.getBookingCode(),
			projection.getTrainNumber(),
			projection.getTrainName(),
			projection.getDepartureStationName(),
			projection.getArrivalStationName(),
			projection.getDepartureTime(),
			projection.getArrivalTime(),
			projection.getOperationDate(),
			tickets
		);
	}
}
