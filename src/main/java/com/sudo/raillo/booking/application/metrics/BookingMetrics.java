package com.sudo.raillo.booking.application.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

@Getter
@Component
public class BookingMetrics {

	private final Counter reservationCreatedCounter;
	private final Counter seatConflictOccupiedCounter;
	private final Timer reservationTimer;
	private final Timer seatOccupancyTimer;

	public BookingMetrics(MeterRegistry meterRegistry) {
		this.reservationCreatedCounter = Counter.builder("reservation_created_total")
			.description("예약 생성 성공 건수")
			.register(meterRegistry);

		this.seatConflictOccupiedCounter = Counter.builder("seat_conflict_total")
			.description("좌석 충돌 건수")
			.tag("conflict_type", "occupied")
			.register(meterRegistry);

		this.reservationTimer = Timer.builder("reservation_duration_seconds")
			.description("예약 생성 전체 소요 시간")
			.publishPercentileHistogram(true)
			.register(meterRegistry);

		this.seatOccupancyTimer = Timer.builder("seat_occupancy_duration_seconds")
			.description("좌석 점유 소요 시간")
			.publishPercentileHistogram(true)
			.register(meterRegistry);
	}

	public void incrementReservationCreated() {
		reservationCreatedCounter.increment();
	}

	public void incrementSeatConflictOccupied() {
		seatConflictOccupiedCounter.increment();
	}
}
