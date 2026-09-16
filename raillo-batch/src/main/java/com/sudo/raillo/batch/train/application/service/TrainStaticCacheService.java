package com.sudo.raillo.batch.train.application.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.sudo.raillo.batch.train.application.dto.StationFareCacheEntry;
import com.sudo.raillo.batch.train.infrastructure.jdbc.TrainCacheJdbcRepository;
import com.sudo.raillo.batch.train.infrastructure.redis.TrainCacheJsonConverter;
import com.sudo.raillo.batch.train.infrastructure.redis.TrainCacheRedisRepository;
import com.sudo.raillo.train.cache.TrainCacheKey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 좌석·객차·역·운임을 Redis에 적재한다.
 *
 * <p>한 번 들어가면 거의 바뀌지 않는 값이라 만료 시각을 걸지 않는다. 시간표를 다시 파싱할 때 갱신된다.</p>
 * <p>DB 트랜잭션을 열지 않는다. 조회는 각자 커넥션을 빌려 끝내고 Redis 쓰기는 그 밖에서 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainStaticCacheService {

	private final TrainCacheJdbcRepository trainCacheJdbcRepository;
	private final TrainCacheRedisRepository trainCacheRedisRepository;
	private final TrainCacheJsonConverter trainCacheJsonConverter;

	public void loadAll() {
		log.info("정적 기준정보 적재 시작");
		loadSeats();
		loadTrainCars();
		loadStations();
		loadStationFares();
		log.info("정적 기준정보 적재 완료");
	}

	private void loadSeats() {
		Map<String, String> entries = new LinkedHashMap<>();
		trainCacheJdbcRepository.findAllSeats().forEach((seatId, seat) ->
			entries.put(TrainCacheKey.seat(seatId), trainCacheJsonConverter.toJson(seat)));

		saveAndPruneStale(entries, TrainCacheKey.seatKeyPattern(), "좌석");
	}

	private void loadTrainCars() {
		Map<String, String> entries = new LinkedHashMap<>();
		trainCacheJdbcRepository.findAllTrainCars().forEach((trainCarId, trainCar) ->
			entries.put(TrainCacheKey.trainCar(trainCarId), trainCacheJsonConverter.toJson(trainCar)));

		saveAndPruneStale(entries, TrainCacheKey.trainCarKeyPattern(), "객차");
	}

	/**
	 * 역명은 짧은 문자열 하나라 JSON으로 감싸지 않는다.
	 */
	private void loadStations() {
		Map<String, String> entries = new LinkedHashMap<>();
		trainCacheJdbcRepository.findAllStationNames().forEach((stationId, stationName) ->
			entries.put(TrainCacheKey.station(stationId), stationName));

		saveAndPruneStale(entries, TrainCacheKey.stationKeyPattern(), "역");
	}

	private void loadStationFares() {
		List<StationFareCacheEntry> fares = trainCacheJdbcRepository.findAllStationFares();

		Map<String, String> fields = new LinkedHashMap<>();
		fares.forEach(entry -> fields.put(
			TrainCacheKey.fareField(entry.departureStationId(), entry.arrivalStationId()),
			entry.fare().serialize()));

		trainCacheRedisRepository.saveHashFields(TrainCacheKey.fare(), fields);

		Set<String> staleFields = new HashSet<>(trainCacheRedisRepository.hashFieldNames(TrainCacheKey.fare()));
		staleFields.removeAll(fields.keySet());
		trainCacheRedisRepository.deleteHashFields(TrainCacheKey.fare(), staleFields);

		log.info("구간 운임 {}건 적재, {}건 정리", fields.size(), staleFields.size());
	}

	/**
	 * 저장한 뒤 DB에 없는 키를 지운다. 순서를 지켜야 키가 비는 구간이 생기지 않는다.
	 */
	private void saveAndPruneStale(Map<String, String> entries, String keyPattern, String label) {
		trainCacheRedisRepository.saveValues(entries);

		if (entries.isEmpty()) {
			log.warn("[{} 정리 건너뜀] DB에서 한 건도 읽지 못해 기존 캐시를 지우지 않는다.", label);
			return;
		}

		Set<String> staleKeys = new HashSet<>(trainCacheRedisRepository.scanKeys(keyPattern));
		staleKeys.removeAll(entries.keySet());
		trainCacheRedisRepository.deleteKeys(staleKeys);

		log.info("{} {}건 적재, {}건 정리", label, entries.size(), staleKeys.size());
	}
}
