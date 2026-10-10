package com.sudo.raillo.payment.application;

import java.util.List;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;

/**
 * 삭제된 `Booking`의 Redis 좌석 점유를 해제하기 위한 계약. `Booking`과 `SeatBooking` 행이 함께
 * 사라지므로 해제에 필요한 운행과 구간, 좌석을 모두 담는다.
 *
 * <p>{@code schemaVersion}은 payload 형식의 버전이다. Outbox 행은 쓴 코드와 읽는 코드가 다른 배포본일
 * 수 있어, 소비자는 지원 버전과 다른 값을 거절한다. 검사가 없으면 Jackson이 없는 필드를 기본값으로 채워
 * 엉뚱한 좌석을 건드린다. 필드가 사라지거나 의미가 바뀔 때 올리고, 선택적 필드 추가처럼 구 JSON으로도
 * 동작하면 올리지 않는다. 절차는 {@code docs/payment-consistency.md}의 "payload 스키마 변경 절차"에 있다.</p>
 *
 * <p>구간의 출처가 쓰는 쪽과 다르다. {@code B:}를 쓴 구간은 Reservation 스냅샷에서 오고
 * ({@code BookingConfirmedPayload}) 여기서 지우는 구간은 `Booking`에서 온다. 지금은 `Booking`이 그
 * 스냅샷에서 파생돼 같은 값이지만 보장이 코드에 없다. 갈라지면 해제가 0건으로 끝나고 좌석이 묶인다.</p>
 */
public record BookingSeatReleasePayload(
	int schemaVersion,
	long bookingId,
	long trainScheduleId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatEntry> seats
) {

	public static final int SCHEMA_VERSION = 1;

	public record SeatEntry(long seatId, long trainCarId) {}

	public static BookingSeatReleasePayload from(Booking booking, List<SeatBooking> seatBookings) {
		if (seatBookings.isEmpty()) {
			throw new IllegalArgumentException("좌석 예매가 없는 예매입니다: " + booking.getId());
		}
		return new BookingSeatReleasePayload(
			SCHEMA_VERSION,
			booking.getId(),
			booking.getTrainSchedule().getId(),
			booking.getDepartureStop().getStopOrder(),
			booking.getArrivalStop().getStopOrder(),
			seatBookings.stream()
				.map(seatBooking -> new SeatEntry(
					seatBooking.getSeat().getId(),
					seatBooking.getSeat().getTrainCar().getId()))
				.toList());
	}
}
