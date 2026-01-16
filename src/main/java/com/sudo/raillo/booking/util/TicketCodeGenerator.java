package com.sudo.raillo.booking.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketCodeGenerator {

	private final RedisTemplate<String, String> stringRedisTemplate;

	private static final String KEY_PREFIX = "ticketSeq:";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMdd");
	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

	/**
	 * 승차권 번호를 생성하는 메서드
	 * 형식: 출발역ID+도착역ID(4자리) - MMdd(4자리) - 예약순번(6자리) - 티켓순서(2자리) = 16자리
	 * 예시: 0218-0116-000001-01
	 */
	public String generate(Long departureStationId, Long arrivalStationId, int ticketIndex) {
		String stationPart = String.format("%02d", departureStationId % 100)
			+ String.format("%02d", arrivalStationId % 100);
		String datePart = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		Long sequence = getNextSequence(stationPart, datePart);

		String sequencePart = String.format("%06d", sequence % 1000000);
		String indexPart = String.format("%02d", ticketIndex % 100);

		return String.join("-", stationPart, datePart, sequencePart, indexPart);
	}

	private Long getNextSequence(String stationPart, String datePart) {
		String redisKey = KEY_PREFIX + stationPart + ":" + datePart;

		Long counter = stringRedisTemplate.opsForValue().increment(redisKey);

		// 자정까지 남은 시간 계산
		ZonedDateTime midnight = ZonedDateTime.of(LocalDate.now(ZONE_ID), LocalTime.MAX, ZONE_ID);
		Instant midnightInstant = midnight.toInstant();

		// 자정 만료
		stringRedisTemplate.expireAt(redisKey, midnightInstant);

		return counter;
	}
}
