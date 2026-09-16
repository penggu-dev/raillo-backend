package com.sudo.raillo.batch.train.application.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sudo.raillo.batch.train.application.dto.ScheduleStopCacheEntry;
import com.sudo.raillo.batch.train.config.TrainCacheProperties;
import com.sudo.raillo.batch.train.infrastructure.jdbc.TrainCacheJdbcRepository;
import com.sudo.raillo.batch.train.infrastructure.redis.TrainCacheJsonConverter;
import com.sudo.raillo.batch.train.infrastructure.redis.TrainCacheRedisRepository;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.TrainCacheKey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운행 정보와 정차역을 Redis에 적재
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainScheduleCacheService {

	private final TrainCacheJdbcRepository trainCacheJdbcRepository;
	private final TrainCacheRedisRepository trainCacheRedisRepository;
	private final TrainCacheJsonConverter trainCacheJsonConverter;
	private final TrainCacheProperties trainCacheProperties;

	public void load(LocalDate startDate, LocalDate endDate) {
		List<ScheduleInfoCacheValue> schedules =
			trainCacheJdbcRepository.findSchedulesBetween(startDate, endDate);

		if (schedules.isEmpty()) {
			log.info("[{} ~ {}] 적재할 운행이 없습니다.", startDate, endDate);
			return;
		}

		saveScheduleInfo(schedules);
		saveScheduleStops(schedules, startDate, endDate);
		log.info("[{} ~ {}] 운행 {}건 적재", startDate, endDate, schedules.size());
	}

	private void saveScheduleInfo(List<ScheduleInfoCacheValue> schedules) {
		Map<LocalDate, Map<String, String>> entriesByDate = new LinkedHashMap<>();

		schedules.forEach(schedule -> entriesByDate
			.computeIfAbsent(schedule.operationDate(), date -> new LinkedHashMap<>())
			.put(TrainCacheKey.scheduleInfo(schedule.trainScheduleId()), trainCacheJsonConverter.toJson(schedule)));

		entriesByDate.forEach((operationDate, entries) ->
			trainCacheRedisRepository.saveValuesExpiringAt(entries, expireAt(operationDate)));
	}

	private void saveScheduleStops(List<ScheduleInfoCacheValue> schedules, LocalDate startDate, LocalDate endDate) {
		Map<Long, LocalDate> operationDateByScheduleId = schedules.stream()
			.collect(Collectors.toMap(
				ScheduleInfoCacheValue::trainScheduleId, ScheduleInfoCacheValue::operationDate));

		Map<Long, Map<String, String>> fieldsByScheduleId = groupStopFields(startDate, endDate);

		Map<LocalDate, Map<String, Map<String, String>>> hashesByDate = new LinkedHashMap<>();
		fieldsByScheduleId.forEach((trainScheduleId, fields) -> {
			LocalDate operationDate = operationDateByScheduleId.get(trainScheduleId);
			if (operationDate == null) {
				log.warn("[정차역 적재 건너뜀] 운행 정보를 찾지 못했습니다. trainScheduleId={}", trainScheduleId);
				return;
			}
			hashesByDate.computeIfAbsent(operationDate, date -> new LinkedHashMap<>())
				.put(TrainCacheKey.scheduleStops(trainScheduleId), fields);
		});

		hashesByDate.forEach((operationDate, hashes) ->
			trainCacheRedisRepository.saveHashesExpiringAt(hashes, expireAt(operationDate)));
	}

	/**
	 * 같은 정차역을 역 ID와 정차역 ID 두 field에 넣는다. 조회 방향이 둘이기 때문이다.
	 */
	private Map<Long, Map<String, String>> groupStopFields(LocalDate startDate, LocalDate endDate) {
		Map<Long, Map<String, String>> fieldsByScheduleId = new LinkedHashMap<>();

		for (ScheduleStopCacheEntry entry : trainCacheJdbcRepository.findScheduleStopsBetween(startDate, endDate)) {
			String json = trainCacheJsonConverter.toJson(entry.stop());
			Map<String, String> fields = fieldsByScheduleId
				.computeIfAbsent(entry.trainScheduleId(), id -> new LinkedHashMap<>());

			fields.put(TrainCacheKey.stopFieldByStation(entry.stop().stationId()), json);
			fields.put(TrainCacheKey.stopFieldByStopId(entry.stop().stopId()), json);
		}
		return fieldsByScheduleId;
	}

	private long expireAt(LocalDate operationDate) {
		return TrainCacheKey.expireAtEpochSecond(operationDate, trainCacheProperties.getRetentionDays());
	}
}
