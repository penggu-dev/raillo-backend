package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;

/**
 * 결제 중 좌석 보호 스크립트 입력.
 *
 * <p>결과를 모르는 동안 좌석을 붙잡는다. 예약 본문은 건드리지 않으므로 본문과 회원 인덱스는 원래 10분 규칙을 따른다.</p>
 *
 * @param keyExpireAtEpochSecond 객차 점유 Hash 키에 만료가 없을 때 걸 운행일 기준 만료 시각
 */
public record SeatHoldCommand(
	long trainScheduleId,
	String reservationId,
	long keyExpireAtEpochSecond,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {
}
