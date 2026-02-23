package com.sudo.raillo.booking.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketNumberGenerator {

	private final RedisTemplate<String, String> stringRedisTemplate;

	private static final String KEY_PREFIX = "ticketSeq:";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMdd");
	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

	/**
	 * 예약 순번을 생성하는 메서드
	 * 형식: MMdd(4자리) - 예약 순번(7자리) = 11자리
	 * 예시: 0117-0000001
	 */
	public String generateReservationCode() {
		String datePart = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);
		Long sequence = getNextReservationCode(datePart);
		return String.format("%s-%07d", datePart, sequence);
	}

	/**
	 * 승차권 번호를 생성하는 메서드
	 * 형식: MMdd(4자리) - 예약 순번(7자리) - 승차권 순번(2자리) = 13자리
	 * 예시: 0117-0000001-01
	 */
	public String generateTicketNumber(String reservationCode, int ticketIndex) {
		return String.format("%s-%02d", reservationCode, ticketIndex);
	}

	private Long getNextReservationCode(String datePart) {
		String redisKey = KEY_PREFIX + datePart;
		Long counter = stringRedisTemplate.opsForValue().increment(redisKey);

		if (counter != null && counter == 1L) {
			Instant tomorrow = LocalDate.now(ZONE_ID).plusDays(1)
				.atStartOfDay(ZONE_ID).toInstant();
			stringRedisTemplate.expireAt(redisKey, tomorrow);
		}

		return counter;
	}
}
