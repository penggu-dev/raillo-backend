package com.sudo.raillo.support.fixture;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.fixture.train.SeatFixture;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.type.SeatType;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TicketFixture {

	private Booking booking;
	private Seat seat = SeatFixture.create(null, 1, "A", SeatType.WINDOW, "N", "Y");
	private PassengerType passengerType = PassengerType.ADULT;
	private BigDecimal fare = BigDecimal.ZERO;
	private String ticketCode = "0218-0116-000001-01";

	public static Ticket create(Booking booking) {
		return builder()
			.withBooking(booking)
			.build();
	}

	// builder method
	public static TicketFixture builder() {
		return new TicketFixture();
	}

	public Ticket build() {
		return Ticket.create(booking, seat, passengerType, ticketCode, fare);
	}

	public TicketFixture withBooking(Booking booking) {
		this.booking = booking;
		return this;
	}

	public TicketFixture withSeat(Seat seat) {
		this.seat = seat;
		return this;
	}

	public TicketFixture withPassengerType(PassengerType passengerType) {
		this.passengerType = passengerType;
		return this;
	}

	public TicketFixture withTicketCode(String ticketCode) {
		this.ticketCode = ticketCode;
		return this;
	}

	public TicketFixture withFare(BigDecimal fare) {
		this.fare = fare;
		return this;
	}
}
