package com.sudo.raillo.booking.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.Seat;

import lombok.Getter;

@Getter
public class SeatInfoProjection {

	private final Seat seat;
	private final PassengerType passengerType;

	@QueryProjection
	public SeatInfoProjection(Seat seat, PassengerType passengerType) {
		this.seat = seat;
		this.passengerType = passengerType;
	}
}
