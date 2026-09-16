package com.sudo.raillo.train.cache;

import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;

/**
 * key: {@code train:seat:{seatId}}
 */
public record SeatCacheValue(
	long trainCarId,
	int carNumber,
	CarType carType,
	int seatRow,
	String seatColumn,
	SeatType seatType
) {
}
