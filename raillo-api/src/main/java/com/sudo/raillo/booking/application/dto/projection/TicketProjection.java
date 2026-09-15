package com.sudo.raillo.booking.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.Getter;

@Getter
public class TicketProjection {

	private final Long ticketId;
	private final Long bookingId;
	private final String ticketNumber;
	private final TicketStatus ticketStatus;
	private final PassengerType passengerType;
	private final int carNumber;
	private final CarType carType;
	private final String seatNumber;

	@QueryProjection
	public TicketProjection(
		Long ticketId,
		Long bookingId,
		String ticketNumber,
		TicketStatus status,
		PassengerType passengerType,
		int carNumber,
		CarType carType,
		String seatNumber
	) {
		this.ticketId = ticketId;
		this.bookingId = bookingId;
		this.ticketNumber = ticketNumber;
		this.ticketStatus = status;
		this.passengerType = passengerType;
		this.carNumber = carNumber;
		this.carType = carType;
		this.seatNumber = seatNumber;
	}
}
