package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;

/**
 * 예매 점유 전환 스크립트 입력.
 *
 * @param keyExpireAtEpochSecond 객차 점유 Hash 키에 만료가 없을 때 걸 운행일 기준 만료 시각
 */
public record BookingOccupancyCommand(
	long trainScheduleId,
	String reservationId,
	long bookingId,
	long keyExpireAtEpochSecond,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
}
