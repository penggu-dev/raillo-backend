package com.sudo.raillo.booking.application.dto;

import com.sudo.raillo.booking.application.dto.projection.BookingProjection;
import com.sudo.raillo.booking.application.dto.projection.SeatBookingProjection;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
	List<SeatBookingProjection> seats
) {

	public static BookingInfo of(
		BookingProjection projection,
		List<SeatBookingProjection> seats
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
			seats
		);
	}
}
