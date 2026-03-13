package com.sudo.raillo.booking.application.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class BookingMetrics {

	private final Counter pendingCreatedCounter;
	private final Counter seatConflictHoldCounter;
	private final Counter seatConflictSoldCounter;

	public BookingMetrics(MeterRegistry meterRegistry) {
		this.pendingCreatedCounter = Counter.builder("pending_booking_created_total")
			.description("PendingBooking 생성 성공 건수")
			.register(meterRegistry);

		this.seatConflictHoldCounter = Counter.builder("seat_conflict_total")
			.description("좌석 충돌 건수")
			.tag("conflict_type", "hold")
			.register(meterRegistry);

		this.seatConflictSoldCounter = Counter.builder("seat_conflict_total")
			.description("좌석 충돌 건수")
			.tag("conflict_type", "sold")
			.register(meterRegistry);
	}

	public void incrementPendingBookingCreated() {
		pendingCreatedCounter.increment();
	}

	public void incrementSeatConflictHold() {
		seatConflictHoldCounter.increment();
	}

	public void incrementSeatConflictSold() {
		seatConflictSoldCounter.increment();
	}
}
