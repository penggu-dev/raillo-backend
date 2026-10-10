package com.sudo.raillo.booking.application.dto;

import java.util.List;

/**
 * 삭제된 `Booking` 하나의 좌석 점유를 해제하는 요청.
 *
 * <p>Reservation ID와 회원 번호가 없다. 해제 대상을 가리키는 식별자는 {@code bookingId}뿐이다.</p>
 */
public record BookingSeatReleaseRequest(
	long trainScheduleId,
	long bookingId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
	public record SeatCar(long seatId, long trainCarId) {
	}
}
