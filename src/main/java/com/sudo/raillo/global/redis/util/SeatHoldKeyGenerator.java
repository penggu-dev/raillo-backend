package com.sudo.raillo.global.redis.util;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 좌석 Hold 관련 Redis 키 생성기
 */
@Component
public class SeatHoldKeyGenerator {

	/**
	 * Redis Cluster 환경을 고려한 Hash Tag 적용
	 * 동일 좌석의 모든 키가 같은 슬롯에 위치하도록 보장
	 */
	private static final String SEAT_HOLD_KEY_FORMAT = "{seat:%d:%d}:hold:%s";
	private static final String SEAT_SOLD_KEY_FORMAT = "{seat:%d:%d}:sold";
	private static final String SEAT_HOLD_PATTERN_FORMAT = "{seat:%d:%d}:hold:*";

	/**
	 * 좌석 임시 점유 키 생성
	 * 예: {seat:1001:12}:hold:pending_abc123
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID
	 */
	public String generateHoldKey(Long trainScheduleId, Long seatId, String pendingBookingId) {
		return String.format(SEAT_HOLD_KEY_FORMAT, trainScheduleId, seatId, pendingBookingId);
	}

	/**
	 * 좌석 확정 키 생성
	 * 예: {seat:1001:12}:sold
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 */
	public String generateSoldKey(Long trainScheduleId, Long seatId) {
		return String.format(SEAT_SOLD_KEY_FORMAT, trainScheduleId, seatId);
	}

	/**
	 * 좌석 Hold 패턴 키 생성 (KEYS 명령용)
	 * 예: {seat:1001:12}:hold:*
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 */
	public String generateHoldPatternKey(Long trainScheduleId, Long seatId) {
		return String.format(SEAT_HOLD_PATTERN_FORMAT, trainScheduleId, seatId);
	}

	/**
	 * 구간 문자열 생성
	 * 예: "1-2" (stopOrder 1에서 2 구간)
	 *
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 */
	public List<String> generateSections(int departureStopOrder, int arrivalStopOrder) {
		return java.util.stream.IntStream
			.range(departureStopOrder, arrivalStopOrder)
			.mapToObj(i -> i + "-" + (i + 1))
			.collect(Collectors.toList());
	}
}
