package com.sudo.raillo.booking.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReservationIdGenerator - 예약 ID 형식")
class ReservationIdGeneratorTest {

	private final ReservationIdGenerator generator = new ReservationIdGenerator();

	@Test
	@DisplayName("RV 접두사, 14자리 시각, 6자리 영대문자·숫자로 22자 ID를 만든다")
	void format() {
		// given

		// when
		String id = generator.generate();

		// then
		assertThat(id).hasSize(ReservationIdGenerator.LENGTH);
		assertThat(id).matches("^RV\\d{14}[0-9A-Z]{6}$");
	}
}
