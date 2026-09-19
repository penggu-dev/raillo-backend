package com.sudo.raillo.train.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StationFareCacheValue - 운임 저장 형식")
class StationFareCacheValueTest {

	@Test
	@DisplayName("일반실과 특실 운임을 구분자로 이어 붙인다")
	void serializesBothFares() {
		// given
		StationFareCacheValue fare = new StationFareCacheValue(
			new BigDecimal("59800"), new BigDecimal("83700"));

		// when
		String value = fare.serialize();

		// then
		assertThat(value).isEqualTo("59800:83700");
	}

	@Test
	@DisplayName("DB 소수 자릿수가 붙어 있어도 뒤따르는 0을 떼어 같은 문자열을 만든다")
	void stripsScaleFromDatabaseValue() {
		// given - DECIMAL 컬럼에서 읽으면 59800.00 형태로 온다
		StationFareCacheValue fromDatabase = new StationFareCacheValue(
			new BigDecimal("59800.00"), new BigDecimal("83700.00"));
		StationFareCacheValue plain = new StationFareCacheValue(
			new BigDecimal("59800"), new BigDecimal("83700"));

		// when

		// then
		assertThat(fromDatabase.serialize()).isEqualTo(plain.serialize());
	}

	@Test
	@DisplayName("직렬화한 값을 다시 읽으면 같은 운임이 된다")
	void roundTrips() {
		// given
		StationFareCacheValue original = new StationFareCacheValue(
			new BigDecimal("59800.00"), new BigDecimal("83700.50"));

		// when
		StationFareCacheValue parsed = StationFareCacheValue.parse(original.serialize());

		// then
		assertThat(parsed.standardFare()).isEqualByComparingTo(original.standardFare());
		assertThat(parsed.firstClassFare()).isEqualByComparingTo(original.firstClassFare());
	}

	@Test
	@DisplayName("소수점이 있는 운임도 자릿수를 잃지 않는다")
	void keepsFractionalFare() {
		// given
		StationFareCacheValue fare = new StationFareCacheValue(
			new BigDecimal("1234.56"), new BigDecimal("7890.10"));

		// when
		String value = fare.serialize();

		// then
		assertThat(value).isEqualTo("1234.56:7890.1");
		assertThat(StationFareCacheValue.parse(value).standardFare())
			.isEqualByComparingTo(new BigDecimal("1234.56"));
	}

	@Test
	@DisplayName("구분자가 없는 값은 거부한다")
	void rejectsMalformedValue() {
		// given

		// when

		// then
		assertThatThrownBy(() -> StationFareCacheValue.parse("59800"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
