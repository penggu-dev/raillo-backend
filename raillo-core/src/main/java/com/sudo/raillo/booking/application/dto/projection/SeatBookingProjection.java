package com.sudo.raillo.booking.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.Getter;

@Getter
public class SeatBookingProjection {

	private final Long seatBookingId;
	private final Long bookingId;
	private final PassengerType passengerType;
	private final int carNumber;
	private final CarType carType;
	private final String seatNumber;

	@QueryProjection
	public SeatBookingProjection(
		Long seatBookingId,
		Long bookingId,
		PassengerType passengerType,
		int carNumber,
		CarType carType,
		String seatNumber
	) {
		this.seatBookingId = seatBookingId;
		this.bookingId = bookingId;
		this.passengerType = passengerType;
		this.carNumber = carNumber;
		this.carType = carType;
		this.seatNumber = seatNumber;
	}
}
