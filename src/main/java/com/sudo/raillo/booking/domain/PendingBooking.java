package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@RedisHash(value = "booking:pendingBooking", timeToLive = 600) // 10분
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingBooking {

	@Id
	private String id;

	private Long memberId;

	private Long trainScheduleId;

	private Long departureStationId;

	private Long arrivalStationId;

	private List<PendingSeatBooking> pendingSeatBookings;

	private BigDecimal totalFare;

	private LocalDateTime createdAt;

	public static PendingBooking create(
		Long memberId,
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId,
		List<PendingSeatBooking> pendingSeatBookings,
		BigDecimal totalFare
	) {
		PendingBooking pendingBooking = new PendingBooking();
		pendingBooking.id = String.valueOf(UUID.randomUUID());
		pendingBooking.memberId = memberId;
		pendingBooking.trainScheduleId = trainScheduleId;
		pendingBooking.departureStationId = departureStationId;
		pendingBooking.arrivalStationId = arrivalStationId;
		pendingBooking.pendingSeatBookings = pendingSeatBookings;
		pendingBooking.totalFare = totalFare;
		pendingBooking.createdAt = LocalDateTime.now();
		return pendingBooking;
	}
}
