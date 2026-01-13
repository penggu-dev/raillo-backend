package com.sudo.raillo.support.fixture;

import java.math.BigDecimal;
import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PendingBookingFixture {

	private String memberNo = "202601010001";
	private Long trainScheduleId = 1L;
	private Long departureStopId = 1L;
	private Long arrivalStopId = 2L;
	private List<PendingSeatBooking> pendingSeatBookings = List.of(new PendingSeatBooking(1L, PassengerType.ADULT));
	private BigDecimal totalFare = BigDecimal.ZERO;

	public static PendingBooking create() {
		return builder().build();
	}

	// builder method
	public static PendingBookingFixture builder() {
		return new PendingBookingFixture();
	}

	public PendingBooking build() {
		return PendingBooking.create(
			memberNo,
			trainScheduleId,
			departureStopId,
			arrivalStopId,
			pendingSeatBookings,
			totalFare
		);
	}

	public PendingBookingFixture withMemberNo(String memberNo) {
		this.memberNo = memberNo;
		return this;
	}

	public PendingBookingFixture withTrainScheduleId(Long trainScheduleId) {
		this.trainScheduleId = trainScheduleId;
		return this;
	}

	public PendingBookingFixture withDepartureStopId(Long departureStopId) {
		this.departureStopId = departureStopId;
		return this;
	}

	public PendingBookingFixture withArrivalStopId(Long arrivalStopId) {
		this.arrivalStopId = arrivalStopId;
		return this;
	}

	public PendingBookingFixture withPendingSeatBookings(List<PendingSeatBooking> pendingSeatBookings) {
		this.pendingSeatBookings = pendingSeatBookings;
		return this;
	}

	public PendingBookingFixture withTotalFare(BigDecimal totalFare) {
		this.totalFare = totalFare;
		return this;
	}
}
