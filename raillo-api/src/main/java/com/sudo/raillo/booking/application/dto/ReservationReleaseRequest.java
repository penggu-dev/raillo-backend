package com.sudo.raillo.booking.application.dto;

import java.util.List;

/** 예매가 되지 못한 예약 하나의 좌석 점유와 본문을 되돌리는 요청. */
public record ReservationReleaseRequest(
	String reservationId,
	String memberNo,
	long trainScheduleId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
	public record SeatCar(long seatId, long trainCarId) {
	}
}
