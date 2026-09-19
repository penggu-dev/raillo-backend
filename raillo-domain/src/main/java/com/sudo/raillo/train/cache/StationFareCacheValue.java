package com.sudo.raillo.train.cache;

import java.math.BigDecimal;

/**
 * key: {@code train:fare}
 */
public record StationFareCacheValue(
	BigDecimal standardFare,
	BigDecimal firstClassFare
) {

	private static final String DELIMITER = ":";

	public String serialize() {
		return format(standardFare) + DELIMITER + format(firstClassFare);
	}

	public static StationFareCacheValue parse(String value) {
		int delimiter = value.indexOf(DELIMITER);
		if (delimiter < 0) {
			throw new IllegalArgumentException("운임 값 형식이 아닙니다: " + value);
		}
		return new StationFareCacheValue(
			new BigDecimal(value.substring(0, delimiter)),
			new BigDecimal(value.substring(delimiter + 1))
		);
	}

	private static String format(BigDecimal fare) {
		return fare.stripTrailingZeros().toPlainString();
	}
}
