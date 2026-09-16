package com.sudo.raillo.batch.train.application.dto;

import com.sudo.raillo.train.cache.StationFareCacheValue;

public record StationFareCacheEntry(
	long departureStationId,
	long arrivalStationId,
	StationFareCacheValue fare
) {
}
