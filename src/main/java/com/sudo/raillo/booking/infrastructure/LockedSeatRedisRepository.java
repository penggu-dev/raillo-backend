package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.domain.LockedSeat;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LockedSeatRedisRepository {

	private final RedisTemplate<String, Object> customObjectRedisTemplate;

	private static final String LOCKED_SEAT_KEY_PREFIX = "lockedSeat:";
	private static final Duration LOCK_EXPIRE_TIME = Duration.ofSeconds(10);

	/**
	 * 좌석 락 저장
	 * Key = lockedSeat:{lockKey}
	 */
	public void save(LockedSeat lockedSeat) {
		String key = LOCKED_SEAT_KEY_PREFIX + lockedSeat.getLockKey();
		customObjectRedisTemplate.opsForValue().set(key, lockedSeat, LOCK_EXPIRE_TIME);
	}

	/**
	 * 좌석 락 조회
	 */
	public Optional<LockedSeat> findByLockKey(String lockKey) {
		String key = LOCKED_SEAT_KEY_PREFIX + lockKey;
		Object value = customObjectRedisTemplate.opsForValue().get(key);
		return Optional.ofNullable((LockedSeat) value);
	}

	/**
	 * 좌석 락 존재 여부 확인
	 */
	public boolean existsByLockKey(String lockKey) {
		String key = LOCKED_SEAT_KEY_PREFIX + lockKey;
		return customObjectRedisTemplate.hasKey(key);
	}

	/**
	 * 좌석 락 삭제
	 */
	public void deleteByLockKey(String lockKey) {
		String key = LOCKED_SEAT_KEY_PREFIX + lockKey;
		customObjectRedisTemplate.delete(key);
	}
}
