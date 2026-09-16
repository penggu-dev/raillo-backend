package com.sudo.raillo.batch.train.infrastructure.jdbc;

import java.util.List;
import java.util.Map;

import com.sudo.raillo.batch.train.application.dto.StationFareCacheEntry;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.TrainCarCacheValue;

public interface TrainCacheJdbcRepository {

	/**
	 * @return 좌석 ID를 키로 하는 좌석 값
	 */
	Map<Long, SeatCacheValue> findAllSeats();

	/**
	 * @return 객차 ID를 키로 하는 객차 값
	 */
	Map<Long, TrainCarCacheValue> findAllTrainCars();

	/**
	 * @return 역 ID를 키로 하는 역명
	 */
	Map<Long, String> findAllStationNames();

	List<StationFareCacheEntry> findAllStationFares();
}
