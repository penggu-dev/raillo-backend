package com.sudo.raillo.train.cache;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 예약 검증용 열차 기준정보 Redis 키 계약.
 *
 * <h2>정적 데이터 (TTL 없음)</h2>
 * <pre>
 * train:seat:{seatId}          String  좌석 정보
 * train:traincar:{trainCarId}  String  객차 정보
 * train:station:{stationId}    String  역명
 * train:fare                   Hash    field: {출발역Id}:{도착역Id}
 * </pre>
 *
 * <h2>일일 운행 데이터 (운행일 기준 만료)</h2>
 * <pre>
 * {schedule:{trainScheduleId}}:info   String  운행 정보
 * {schedule:{trainScheduleId}}:stops  Hash    field: st:{역Id} 와 id:{정차역Id}
 * </pre>
 *
 * <p>운행 단위 키는 {@code {schedule:id}} hash tag를 쓴다. 좌석 Hold 키와 같은 형식이라
 * Redis Cluster에서 한 운행의 모든 키가 같은 slot에 놓인다.</p>
 */
public final class TrainCacheKey {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	/** 운행일로부터 캐시를 유지할 일수. 자정을 넘겨 운행하는 열차와 출발 직전 예약을 고려한 값이다. */
	public static final int DEFAULT_RETENTION_DAYS = 2;

	private static final String SCHEDULE_PREFIX = "{schedule:%d}:";

	private static final String SEAT_KEY = "train:seat:%d";
	private static final String TRAIN_CAR_KEY = "train:traincar:%d";
	private static final String STATION_KEY = "train:station:%d";
	private static final String FARE_KEY = "train:fare";

	private static final String SEAT_KEY_PATTERN = "train:seat:*";
	private static final String TRAIN_CAR_KEY_PATTERN = "train:traincar:*";
	private static final String STATION_KEY_PATTERN = "train:station:*";

	private static final String SCHEDULE_INFO_KEY = SCHEDULE_PREFIX + "info";
	private static final String SCHEDULE_STOPS_KEY = SCHEDULE_PREFIX + "stops";

	private static final String FARE_FIELD = "%d:%d";
	private static final String STOP_FIELD_BY_STATION = "st:%d";
	private static final String STOP_FIELD_BY_STOP = "id:%d";

	private TrainCacheKey() {
	}

	/* 정적 데이터 키 */

	public static String seat(long seatId) {
		return SEAT_KEY.formatted(seatId);
	}

	public static String trainCar(long trainCarId) {
		return TRAIN_CAR_KEY.formatted(trainCarId);
	}

	public static String station(long stationId) {
		return STATION_KEY.formatted(stationId);
	}

	public static String fare() {
		return FARE_KEY;
	}

	/** 운임 Hash의 field. 구간은 방향을 구분하므로 출발역과 도착역의 순서가 의미를 가진다. */
	public static String fareField(long departureStationId, long arrivalStationId) {
		return FARE_FIELD.formatted(departureStationId, arrivalStationId);
	}

	/* 일일 운행 데이터 키 */

	public static String scheduleInfo(long trainScheduleId) {
		return SCHEDULE_INFO_KEY.formatted(trainScheduleId);
	}

	public static String scheduleStops(long trainScheduleId) {
		return SCHEDULE_STOPS_KEY.formatted(trainScheduleId);
	}

	/**
	 * 역 ID로 정차역을 찾는 field. 예약 생성 시 출발역·도착역으로 조회하는 경로가 쓴다.
	 */
	public static String stopFieldByStation(long stationId) {
		return STOP_FIELD_BY_STATION.formatted(stationId);
	}

	/**
	 * 정차역 ID로 정차역을 찾는 field. 예약이 저장해 둔 정차역 ID로 되짚는 경로가 쓴다.
	 */
	public static String stopFieldByStopId(long scheduleStopId) {
		return STOP_FIELD_BY_STOP.formatted(scheduleStopId);
	}

	/* 오래된 키 정리용 SCAN 패턴과 ID 역추출 */

	public static String seatKeyPattern() {
		return SEAT_KEY_PATTERN;
	}

	public static String trainCarKeyPattern() {
		return TRAIN_CAR_KEY_PATTERN;
	}

	public static String stationKeyPattern() {
		return STATION_KEY_PATTERN;
	}

	/**
	 * 위 패턴으로 SCAN 한 키에서 ID를 꺼낸다. 세 키 모두 마지막 구분자 뒤가 ID다.
	 *
	 * @throws IllegalArgumentException 키에 숫자 ID가 없을 때
	 */
	public static long idFromKey(String key) {
		int lastColon = key.lastIndexOf(':');
		if (lastColon < 0 || lastColon == key.length() - 1) {
			throw new IllegalArgumentException("ID를 가진 기준정보 키가 아닙니다: " + key);
		}
		try {
			return Long.parseLong(key.substring(lastColon + 1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("ID를 가진 기준정보 키가 아닙니다: " + key, e);
		}
	}

	/* 만료 시각 */

	/**
	 * 운행 단위 키의 만료 시각을 절대 시각으로 계산한다.
	 *
	 * <p>적재 시점 기준 상대 TTL을 쓰면 안 된다. 월간 스케줄 생성 Job이 한 달치를 미리 만들기 때문에
	 * 상대 TTL은 운행일이 오기도 전에 만료된다.</p>
	 *
	 * @param operationDate 운행일
	 * @param retentionDays 운행일로부터 유지할 일수
	 * @return 만료 시각 (Unix epoch 초)
	 */
	public static long expireAtEpochSecond(LocalDate operationDate, int retentionDays) {
		return operationDate.plusDays(retentionDays)
			.atStartOfDay(ZONE)
			.toEpochSecond();
	}

	public static long expireAtEpochSecond(LocalDate operationDate) {
		return expireAtEpochSecond(operationDate, DEFAULT_RETENTION_DAYS);
	}
}
