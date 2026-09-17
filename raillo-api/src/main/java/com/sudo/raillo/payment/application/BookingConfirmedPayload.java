package com.sudo.raillo.payment.application;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;

/**
 * PaymentOutboxWorker의 BookingConfirmedProcessor가 승인 확정 후 Redis 정리에 참조하는 최소 정보.
 *
 * <p>trainCarId·stopOrder는 processor가 실행 시점에 ScheduleStop·Seat 조회로 채우므로 payload에 담지 않는다.
 */
public record BookingConfirmedPayload(List<Entry> pendingBookings) {

	public record Entry(
		String pendingBookingId,
		String memberNo,
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<Long> seatIds
	) {}

	public static BookingConfirmedPayload from(List<PendingBooking> pendingBookings) {
		List<Entry> entries = pendingBookings.stream()
			.map(pb -> new Entry(
				pb.getId(),
				pb.getMemberNo(),
				pb.getTrainScheduleId(),
				pb.getDepartureStopId(),
				pb.getArrivalStopId(),
				pb.getSeatIds()
			))
			.toList();
		return new BookingConfirmedPayload(entries);
	}
}
