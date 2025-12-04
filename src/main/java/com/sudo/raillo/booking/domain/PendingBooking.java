package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingBooking {

	private String id;

	private String memberNo;

	private Long trainScheduleId;

	private Long departureStationId;

	private Long arrivalStationId;

	private List<PendingSeatBooking> pendingSeatBookings;

	private BigDecimal totalFare;

	private LocalDateTime createdAt;

	public static PendingBooking create(
		String memberNo,
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId,
		List<PendingSeatBooking> pendingSeatBookings,
		BigDecimal totalFare
	) {
		PendingBooking pendingBooking = new PendingBooking();
		pendingBooking.id = String.valueOf(UUID.randomUUID());
		pendingBooking.memberNo = memberNo;
		pendingBooking.trainScheduleId = trainScheduleId;
		pendingBooking.departureStationId = departureStationId;
		pendingBooking.arrivalStationId = arrivalStationId;
		pendingBooking.pendingSeatBookings = pendingSeatBookings;
		pendingBooking.totalFare = totalFare;
		pendingBooking.createdAt = LocalDateTime.now();
		return pendingBooking;
	}
}
