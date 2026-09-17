package com.sudo.raillo.booking.infrastructure;

import java.util.List;

/**
 * 좌석 점유 생성 스크립트 입력.
 *
 * @param ttlSeconds 점유 field와 예약 키의 만료 (초, 1 이상)
 * @param keyExpireAtEpochSecond 객차 점유 Hash 키의 만료 시각. 운행일 기준 절대 시각이다
 * @param reservationJson 예약 키에 저장할 본문
 */
public record SeatOccupancyHoldCommand(
	long trainScheduleId,
	String reservationId,
	long ttlSeconds,
	long keyExpireAtEpochSecond,
	String reservationJson,
	int departureStopOrder,
	int arrivalStopOrder,
	List<SeatCar> seats
) {

	public record SeatCar(long seatId, long trainCarId) {
	}
}
