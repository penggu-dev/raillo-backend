package com.sudo.raillo.train.cache;

import com.sudo.raillo.train.domain.type.CarType;

/**
 * key: {@code train:traincar:{trainCarId}}
 */
public record TrainCarCacheValue(
	long trainId,
	int carNumber,
	CarType carType,
	int seatRowCount,
	int totalSeats,
	String seatArrangement
) {
}
