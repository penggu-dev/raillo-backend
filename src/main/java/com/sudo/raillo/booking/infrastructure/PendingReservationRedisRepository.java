package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.domain.PendingReservation;
import com.sudo.raillo.global.redis.RedisKeyGenerator;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PendingReservationRedisRepository {

	private final RedisTemplate<String, Object> objectRedisTemplate;
	private final RedisKeyGenerator redisKeyGenerator;

	// 외부 변수로 변경
	private static final Duration PENDING_RESERVATION_EXPIRE_TIME = Duration.ofMinutes(10);

	/**
	 * 임시 예약 저장
	 * Key = booking:pendingReservation:{pendingId}
	 */
	public void save(PendingReservation pendingReservation) {
		String key = redisKeyGenerator.generatePendingReservationKey(pendingReservation.getPendingId());
		objectRedisTemplate.opsForValue()
			.set(key, pendingReservation, PENDING_RESERVATION_EXPIRE_TIME);
	}

	/**
	 * 임시 예약 조회
	 */
	public Optional<PendingReservation> findById(String pendingId) {
		String key = redisKeyGenerator.generatePendingReservationKey(pendingId);
		Object value = objectRedisTemplate.opsForValue().get(key);
		return Optional.ofNullable((PendingReservation)value);
	}

	/**
	 * 임시 예약 삭제
	 */
	public void deleteById(String pendingId) {
		String key = redisKeyGenerator.generatePendingReservationKey(pendingId);
		objectRedisTemplate.delete(key);
	}

	/**
	 * 임시 예약 존재 여부 확인
	 */
	public boolean existsById(String pendingId) {
		String key = redisKeyGenerator.generatePendingReservationKey(pendingId);
		return objectRedisTemplate.hasKey(key);
	}
}
