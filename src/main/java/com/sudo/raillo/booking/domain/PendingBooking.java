package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class PendingBooking {

	private final String id;

	private final String memberNo;

	private final Long trainScheduleId;

 	private final Long departureStopId;

	private final Long arrivalStopId;

	private final List<PendingSeatBooking> pendingSeatBookings;

	private final BigDecimal totalFare;

	private final LocalDateTime createdAt;

	@JsonCreator
	private PendingBooking(
		@JsonProperty("id") String id,
		@JsonProperty("memberNo") String memberNo,
		@JsonProperty("trainScheduleId") Long trainScheduleId,
		@JsonProperty("departureStopId") Long departureStopId,
		@JsonProperty("arrivalStopId") Long arrivalStopId,
		@JsonProperty("pendingSeatBookings") List<PendingSeatBooking> pendingSeatBookings,
		@JsonProperty("totalFare") BigDecimal totalFare,
		@JsonProperty("createdAt") LocalDateTime createdAt
	) {
		this.id = id;
		this.memberNo = memberNo;
		this.trainScheduleId = trainScheduleId;
		this.departureStopId = departureStopId;
		this.arrivalStopId = arrivalStopId;
		this.pendingSeatBookings = pendingSeatBookings;
		this.totalFare = totalFare;
		this.createdAt = createdAt;
	}

	public static PendingBooking create(
		String id,
		String memberNo,
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<PendingSeatBooking> pendingSeatBookings,
		BigDecimal totalFare
	) {
		return new PendingBooking(
			id,
			memberNo,
			trainScheduleId,
			departureStopId,
			arrivalStopId,
			List.copyOf(pendingSeatBookings),
			totalFare,
			LocalDateTime.now()
		);
	}
}
