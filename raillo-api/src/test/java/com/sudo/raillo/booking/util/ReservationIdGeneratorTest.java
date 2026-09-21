package com.sudo.raillo.booking.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

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

	@Test
	@DisplayName("같은 초에 만든 ID도 서로 다르다")
	void unique() {
		// given
		Set<String> ids = new HashSet<>();

		// when
		IntStream.range(0, 10_000).forEach(i -> ids.add(generator.generate()));

		// then
		assertThat(ids).hasSize(10_000);
	}
}
