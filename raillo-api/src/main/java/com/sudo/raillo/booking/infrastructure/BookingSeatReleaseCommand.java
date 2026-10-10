package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;

/**
 * `Booking` 점유 해제 스크립트 입력. `Booking`이 점유했던 운행과 구간, 좌석을 그대로 넘긴다.
 *
 * <p>Reservation ID가 없다. R에서 B로 전환될 때 Reservation 본문이 사라지므로 식별자는 {@code bookingId}뿐이다.</p>
 */
public record BookingSeatReleaseCommand(
	long trainScheduleId,
	long bookingId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
}
