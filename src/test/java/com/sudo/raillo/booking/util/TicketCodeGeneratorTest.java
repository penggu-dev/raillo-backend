package com.sudo.raillo.booking.util;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class TicketCodeGeneratorTest {

	@Autowired
	private TicketCodeGenerator ticketCodeGenerator;

	@Autowired
	private RedisTemplate<String, String> stringRedisTemplate;

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMdd");

	@Test
	@DisplayName("승차권 번호가 올바른 형식으로 생성된다")
	void generate_success() {
		// given
		Long departureStationId = 2L;
		Long arrivalStationId = 18L;
		int ticketIndex = 1;
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		// when
		String ticketCode = ticketCodeGenerator.generate(departureStationId, arrivalStationId, ticketIndex);

		// then
		assertThat(ticketCode).matches("\\d{4}-\\d{4}-\\d{6}-\\d{2}");
		assertThat(ticketCode).startsWith("0218-" + today);
		assertThat(ticketCode).endsWith("-01");
	}

	@Test
	@DisplayName("같은 구간에서 연속 생성 시 순서가 증가한다")
	void generate_sequence_increments_for_same_route() {
		// given
		Long departureStationId = 74L;
		Long arrivalStationId = 12L;
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);
		String stationPart = "7412";
		String redisKey = "ticketSeq:" + stationPart + ":" + today;

		// when
		String ticketCode1 = ticketCodeGenerator.generate(departureStationId, arrivalStationId, 1);
		String ticketCode2 = ticketCodeGenerator.generate(departureStationId, arrivalStationId, 2);

		// then
		assertThat(ticketCode1).isEqualTo(stationPart + "-" + today + "-000001-01");
		assertThat(ticketCode2).isEqualTo(stationPart + "-" + today + "-000002-02");

		String counterValue = stringRedisTemplate.opsForValue().get(redisKey);
		assertThat(counterValue).isEqualTo("2");
	}

	@Test
	@DisplayName("다른 구간에서는 별도의 순서가 관리된다")
	void generate_separate_sequence_for_different_routes() {
		// given
		Long departureStationId1 = 1L;
		Long arrivalStationId1 = 2L;
		Long departureStationId2 = 3L;
		Long arrivalStationId2 = 4L;
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		// when
		String ticketCode1 = ticketCodeGenerator.generate(departureStationId1, arrivalStationId1, 1);
		String ticketCode2 = ticketCodeGenerator.generate(departureStationId2, arrivalStationId2, 1);

		// then
		assertThat(ticketCode1).isEqualTo("0102-" + today + "-000001-01");
		assertThat(ticketCode2).isEqualTo("0304-" + today + "-000001-01");
	}
}
