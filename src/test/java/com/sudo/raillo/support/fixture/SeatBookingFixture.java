package com.sudo.raillo.support.fixture;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

import com.sudo.raillo.train.domain.type.SeatType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SeatBookingFixture {

	private Booking booking;
	private TrainSchedule trainSchedule;
	private Seat seat = SeatFixture.create(null, 1, "A", SeatType.WINDOW, "N", "Y");
	private PassengerType passengerType = PassengerType.ADULT;

	public static SeatBooking create(Booking booking) {
		return builder()
			.withBooking(booking)
			.withTrainSchedule(booking.getTrainSchedule())
			.build();
	}

	// builder method
	public static SeatBookingFixture builder() {
		return new SeatBookingFixture();
	}

	public SeatBooking build() {
		return SeatBooking.create(trainSchedule, seat, booking, passengerType);
	}

	public SeatBookingFixture withBooking(Booking booking) {
		this.booking = booking;
		return this;
	}

	public SeatBookingFixture withTrainSchedule(TrainSchedule trainSchedule) {
		this.trainSchedule = trainSchedule;
		return this;
	}

	public SeatBookingFixture withSeat(Seat seat) {
		this.seat = seat;
		return this;
	}

	public SeatBookingFixture withPassengerType(PassengerType passengerType) {
		this.passengerType = passengerType;
		return this;
	}
}
