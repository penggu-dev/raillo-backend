package com.sudo.raillo.booking.application.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

@Getter
@Component
public class BookingMetrics {

	private final Counter pendingCreatedCounter;
	private final Counter seatConflictHoldCounter;
	private final Counter seatConflictSoldCounter;
	private final Timer pendingBookingTimer;
	private final Timer seatHoldTimer;

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

		this.pendingBookingTimer = Timer.builder("pending_booking_duration_seconds")
			.description("예약 생성 전체 소요 시간")
			.publishPercentileHistogram(true)
			.register(meterRegistry);

		this.seatHoldTimer = Timer.builder("seat_hold_duration_seconds")
			.description("Seat Hold 소요 시간")
			.publishPercentileHistogram(true)
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
