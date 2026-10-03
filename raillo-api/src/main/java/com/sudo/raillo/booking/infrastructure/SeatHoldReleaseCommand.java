package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;

/**
 * 결제 중 좌석 보호 해제 스크립트 입력.
 *
 * <p>자기 예약 field의 만료를 보호 이전 상태로 되돌린다. 자기 것이 아닌 field는 건드리지 않는다.</p>
 */
public record SeatHoldReleaseCommand(
	long trainScheduleId,
	String reservationId,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
}
