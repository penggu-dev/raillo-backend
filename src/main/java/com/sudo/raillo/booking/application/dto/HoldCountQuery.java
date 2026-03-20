package com.sudo.raillo.booking.application.dto;

import java.util.List;

/**
 * Seat Hold 점유 좌석 수 배치 조회 요청 단위
 *
 * @param trainScheduleId 열차 스케줄 ID
 * @param trainCarIds 조회할 객차 ID 목록
 * @param departureStopOrder 출발역 stopOrder
 * @param arrivalStopOrder 도착역 stopOrder
 */
public record HoldCountQuery(
	Long trainScheduleId,
	List<Long> trainCarIds,
	int departureStopOrder,
	int arrivalStopOrder
) {
}
