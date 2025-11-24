package com.sudo.raillo.booking.application.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.sudo.raillo.booking.application.dto.projection.ReservationProjection;
import com.sudo.raillo.booking.application.dto.projection.SeatReservationProjection;

public record ReservationInfo(
	Long reservationId,
	String reservationCode,
	int trainNumber,
	String trainName,
	String departureStationName,
	String arrivalStationName,
	LocalTime departureTime,
	LocalTime arrivalTime,
	LocalDate operationDate,
	int fare,
	List<SeatReservationProjection> seats
) {

	public static ReservationInfo of(
		ReservationProjection projection,
		List<SeatReservationProjection> seats
	) {
		return new ReservationInfo(
			projection.getReservationId(),
			projection.getReservationCode(),
			projection.getTrainNumber(),
			projection.getTrainName(),
			projection.getDepartureStationName(),
			projection.getArrivalStationName(),
			projection.getDepartureTime(),
			projection.getArrivalTime(),
			projection.getOperationDate(),
			projection.getFare(),
			seats
		);
	}
}
