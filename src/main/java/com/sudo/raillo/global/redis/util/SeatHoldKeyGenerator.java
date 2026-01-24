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
	private static final String SEAT_HOLDS_KEY_FORMAT = "{seat:%d:%d}:holds";

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
	 * 좌석 Hold 목록 인덱스 키 생성
	 *
	 * <p>예: {@code {seat:1001:12}:holds}</p>
	 * <p>해당 좌석의 현재 Hold 목록(pendingBookingId들)을 저장하는 Set의 키</p>
	 * <p>KEYS 명령 대신 SMEMBERS로 Hold 목록을 조회하기 위한 인덱스 역할</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @return Redis 키
	 */
	public String generateHoldsKey(Long trainScheduleId, Long seatId) {
		return String.format(SEAT_HOLDS_KEY_FORMAT, trainScheduleId, seatId);
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
