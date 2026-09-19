package com.sudo.raillo.batch.train.infrastructure.jdbc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.sudo.raillo.batch.train.application.dto.ScheduleStopCacheEntry;
import com.sudo.raillo.batch.train.application.dto.StationFareCacheEntry;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
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

	/**
	 * @param startDate 운행일 범위 시작 (포함)
	 * @param endDate 운행일 범위 끝 (포함)
	 */
	List<ScheduleInfoCacheValue> findSchedulesBetween(LocalDate startDate, LocalDate endDate);

	List<ScheduleStopCacheEntry> findScheduleStopsBetween(LocalDate startDate, LocalDate endDate);
}
