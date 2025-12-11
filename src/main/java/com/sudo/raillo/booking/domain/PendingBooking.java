package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

@Getter
public class PendingBooking {

	private final String id;

	private final String memberNo;

	private final Long trainScheduleId;

	private final Long departureStationId;

	private final Long arrivalStationId;

	private final List<PendingSeatBooking> pendingSeatBookings;

	private final BigDecimal totalFare;

	private final LocalDateTime createdAt;

	@JsonCreator
	private PendingBooking(
		@JsonProperty("id") String id,
		@JsonProperty("memberNo") String memberNo,
		@JsonProperty("trainScheduleId") Long trainScheduleId,
		@JsonProperty("departureStationId") Long departureStationId,
		@JsonProperty("arrivalStationId") Long arrivalStationId,
		@JsonProperty("pendingSeatBookings") List<PendingSeatBooking> pendingSeatBookings,
		@JsonProperty("totalFare") BigDecimal totalFare,
		@JsonProperty("createdAt") LocalDateTime createdAt
	) {
		this.id = id;
		this.memberNo = memberNo;
		this.trainScheduleId = trainScheduleId;
		this.departureStationId = departureStationId;
		this.arrivalStationId = arrivalStationId;
		this.pendingSeatBookings = pendingSeatBookings;
		this.totalFare = totalFare;
		this.createdAt = createdAt;
	}

	public static PendingBooking create(
		String memberNo,
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId,
		List<PendingSeatBooking> pendingSeatBookings,
		BigDecimal totalFare
	) {
		return new PendingBooking(
			String.valueOf(UUID.randomUUID()),
			memberNo,
			trainScheduleId,
			departureStationId,
			arrivalStationId,
			List.copyOf(pendingSeatBookings),
			totalFare,
			LocalDateTime.now()
		);
	}
}
