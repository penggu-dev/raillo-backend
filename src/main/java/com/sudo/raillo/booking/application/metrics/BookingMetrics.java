package com.sudo.raillo.booking.application.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class BookingMetrics {

	private final Counter pendingCreatedCounter;
	private final Counter pendingDeletedCounter;
	private final Counter seatConflictCounter;

	public BookingMetrics(MeterRegistry meterRegistry) {
		this.pendingCreatedCounter = Counter.builder("pending_booking_created_total")
			.description("PendingBooking 생성 성공 건수")
			.register(meterRegistry);

		this.pendingDeletedCounter = Counter.builder("pending_booking_deleted_total")
			.description("사용자 직접 취소 건수")
			.register(meterRegistry);

		this.seatConflictCounter = Counter.builder("seat_conflict_hold_total")
			.description("좌석 충돌 건수")
			.register(meterRegistry);
	}

	public void incrementPendingBookingCreated() {
		pendingCreatedCounter.increment();
	}

	public void incrementPendingBookingDeleted(int count) {
		pendingDeletedCounter.increment(count);
	}

	public void incrementSeatConflict() {
		seatConflictCounter.increment();
	}
}
