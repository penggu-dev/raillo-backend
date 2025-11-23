package com.sudo.raillo.booking.redis;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisIdGenerator {

	private static final String RESERVATION_SEQUENCE_KEY = "reservation:sequence"; // id 생성 key
	private static final String SEAT_RESERVATION_SEQUENCE_KEY = "seat-reservation:sequence";
	private final RedisTemplate<String, Object> objectRedisTemplate;

	public Long generateReservationId() {
		return objectRedisTemplate.opsForValue().increment(RESERVATION_SEQUENCE_KEY,1L);
	}

	public Long generateSeatReservationId() {
		return objectRedisTemplate.opsForValue().increment(SEAT_RESERVATION_SEQUENCE_KEY, 1L);
	}
}
