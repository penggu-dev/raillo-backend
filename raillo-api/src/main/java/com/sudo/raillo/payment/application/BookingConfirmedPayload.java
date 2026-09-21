package com.sudo.raillo.payment.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.sudo.raillo.payment.application.result.ConfirmedBookingResult;
import com.sudo.raillo.booking.domain.Reservation;

/** Reservation TTL과 독립적인 후속 R→B 확정 계약. */
public record BookingConfirmedPayload(int schemaVersion, long paymentId, String attemptId, List<Entry> bookings) {
	public record SeatEntry(long seatId, long trainCarId) {}
	public record Entry(String reservationId, long bookingId, String memberNo, long trainScheduleId,
		LocalDate operationDate, int departureStopOrder, int arrivalStopOrder, List<SeatEntry> seats) {}

	public static BookingConfirmedPayload from(long paymentId, com.sudo.raillo.payment.domain.PaymentAttempt attempt,
		List<Reservation> reservations, List<ConfirmedBookingResult> confirmed) {
		Map<String, Long> ids = confirmed.stream().collect(Collectors.toMap(
			ConfirmedBookingResult::reservationId, ConfirmedBookingResult::bookingId));
		if (reservations.isEmpty() || ids.size() != reservations.size()) {
			throw new IllegalArgumentException("예약과 생성된 예매의 매핑이 일치해야 합니다");
		}
		var entries = reservations.stream().map(r -> {
			Long bookingId = ids.get(r.reservationId());
			if (bookingId == null) throw new IllegalArgumentException("예매 ID가 없는 예약입니다: " + r.reservationId());
			return new Entry(r.reservationId(), bookingId, r.memberNo(), r.trainScheduleId(), r.operationDate(),
				r.departure().stopOrder(), r.arrival().stopOrder(),
				r.seats().stream().map(s -> new SeatEntry(s.seatId(), s.trainCarId())).toList());
		}).toList();
		return new BookingConfirmedPayload(2, paymentId, attempt.getAttemptId(), entries);
	}
}
