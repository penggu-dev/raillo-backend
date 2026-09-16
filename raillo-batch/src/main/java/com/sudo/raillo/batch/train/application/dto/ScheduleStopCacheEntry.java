package com.sudo.raillo.batch.train.application.dto;

import com.sudo.raillo.train.cache.ScheduleStopCacheValue;

public record ScheduleStopCacheEntry(
	long trainScheduleId,
	ScheduleStopCacheValue stop
) {
}
