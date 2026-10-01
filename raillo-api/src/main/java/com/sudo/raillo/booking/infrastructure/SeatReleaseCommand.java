package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;

/**
 * 좌석 점유 해제 스크립트 입력. 예약이 점유했던 운행·구간·좌석을 그대로 넘긴다.
 */
public record SeatReleaseCommand(
	long trainScheduleId,
	String reservationId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
}
