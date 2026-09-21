package com.sudo.raillo.train.application.dto;

import java.util.List;

import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;

public record TrainCacheSnapshot(
	ScheduleInfoCacheValue schedule,
	ScheduleStopCacheValue departureStop,
	ScheduleStopCacheValue arrivalStop,
	List<SeatCacheValue> seats,
	StationFareCacheValue fare
) {
}
