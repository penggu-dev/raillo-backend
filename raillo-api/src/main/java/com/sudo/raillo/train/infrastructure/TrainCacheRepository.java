package com.sudo.raillo.train.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.train.application.dto.TrainCacheSnapshot;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.cache.TrainCacheKey;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrainCacheRepository {

	private final StringRedisTemplate stringRedisTemplate;
	private final RedisJsonConverter redisJsonConverter;

	/**
	 * 예약 생성에 필요한 운행·정차역·좌석·운임을 파이프라인 한 번으로 읽는다.
	 */
	public TrainCacheSnapshot fetchForReservation(
		long trainScheduleId,
		long departureStationId,
		long arrivalStationId,
		List<Long> seatIds
	) {
		byte[] scheduleInfoKey = bytes(TrainCacheKey.scheduleInfo(trainScheduleId));
		byte[] scheduleStopsKey = bytes(TrainCacheKey.scheduleStops(trainScheduleId));
		byte[] departureStopField = bytes(TrainCacheKey.stopFieldByStation(departureStationId));
		byte[] arrivalStopField = bytes(TrainCacheKey.stopFieldByStation(arrivalStationId));
		byte[][] seatKeys = seatIds.stream()
			.map(seatId -> bytes(TrainCacheKey.seat(seatId)))
			.toArray(byte[][]::new);
		byte[] fareKey = bytes(TrainCacheKey.fare());
		byte[] fareField = bytes(TrainCacheKey.fareField(departureStationId, arrivalStationId));

		List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>)connection -> {
			connection.stringCommands().get(scheduleInfoKey);
			connection.hashCommands().hMGet(scheduleStopsKey, departureStopField, arrivalStopField);
			connection.stringCommands().mGet(seatKeys);
			connection.hashCommands().hGet(fareKey, fareField);
			return null;
		});

		String scheduleJson = (String)results.get(0);
		List<String> stopJsons = castList(results.get(1));
		List<String> seatJsons = castList(results.get(2));
		String fareValue = (String)results.get(3);

		return new TrainCacheSnapshot(
			parse(scheduleJson, ScheduleInfoCacheValue.class),
			parse(stopJsons.get(0), ScheduleStopCacheValue.class),
			parse(stopJsons.get(1), ScheduleStopCacheValue.class),
			seatJsons.stream().map(json -> parse(json, SeatCacheValue.class)).toList(),
			fareValue == null ? null : StationFareCacheValue.parse(fareValue)
		);
	}

	private <T> T parse(String json, Class<T> type) {
		return json == null ? null : redisJsonConverter.fromJson(json, type);
	}

	@SuppressWarnings("unchecked")
	private static List<String> castList(Object result) {
		return (List<String>)Objects.requireNonNull(result);
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
