package com.sudo.raillo.support.fixture;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.fixture.train.SeatFixture;
import com.sudo.raillo.train.domain.Seat;

import com.sudo.raillo.train.domain.type.SeatType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TicketFixture {

	private Booking booking;
	private Seat seat = SeatFixture.create(null, 1, "A", SeatType.WINDOW, "N", "Y");
	private TicketStatus ticketStatus = TicketStatus.ISSUED;
	private PassengerType passengerType = PassengerType.ADULT;
	private String vendorCode = "01";
	private String purchaseDate = "0101";
	private String purchaseSeq = "10001";
	private String purchaseUid = "01";

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
		return Ticket.builder()
			.booking(booking)
			.seat(seat)
			.ticketStatus(ticketStatus)
			.passengerType(passengerType)
			.vendorCode(vendorCode)
			.purchaseDate(purchaseDate)
			.purchaseSeq(purchaseSeq)
			.purchaseUid(purchaseUid)
			.build();
	}

	public TicketFixture withBooking(Booking booking) {
		this.booking = booking;
		return this;
	}

	public TicketFixture withSeat(Seat seat) {
		this.seat = seat;
		return this;
	}

	public TicketFixture withTicketStatus(TicketStatus ticketStatus) {
		this.ticketStatus = ticketStatus;
		return this;
	}

	public TicketFixture withPassengerType(PassengerType passengerType) {
		this.passengerType = passengerType;
		return this;
	}
}
