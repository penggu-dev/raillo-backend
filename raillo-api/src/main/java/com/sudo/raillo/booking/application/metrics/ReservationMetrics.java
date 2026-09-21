package com.sudo.raillo.booking.application.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

@Getter
@Component
public class ReservationMetrics {

	private final Counter reservationCreatedCounter;
	private final Counter seatConflictWithReservationCounter;
	private final Counter seatConflictWithBookingCounter;
	private final Timer reservationTimer;
	private final Timer seatOccupancyTimer;

	public ReservationMetrics(MeterRegistry meterRegistry) {
		this.reservationCreatedCounter = Counter.builder("reservation_created_total")
			.description("예약 생성 성공 건수")
			.register(meterRegistry);

		this.seatConflictWithReservationCounter = seatConflictCounter(meterRegistry, "reservation");
		this.seatConflictWithBookingCounter = seatConflictCounter(meterRegistry, "booking");

		this.reservationTimer = Timer.builder("reservation_duration_seconds")
			.description("예약 생성 전체 소요 시간")
			.publishPercentileHistogram(true)
			.register(meterRegistry);

		this.seatOccupancyTimer = Timer.builder("seat_occupancy_duration_seconds")
			.description("좌석 점유 스크립트 소요 시간")
			.publishPercentileHistogram(true)
			.register(meterRegistry);
	}

	public void incrementReservationCreated() {
		reservationCreatedCounter.increment();
	}

	public void incrementSeatConflictWithReservation() {
		seatConflictWithReservationCounter.increment();
	}

	public void incrementSeatConflictWithBooking() {
		seatConflictWithBookingCounter.increment();
	}

	private static Counter seatConflictCounter(MeterRegistry meterRegistry, String conflictType) {
		return Counter.builder("seat_conflict_total")
			.description("좌석 점유 충돌 건수 (conflict_type: reservation=다른 예약과 충돌, booking=예매된 좌석과 충돌)")
			.tag("conflict_type", conflictType)
			.register(meterRegistry);
	}
}
