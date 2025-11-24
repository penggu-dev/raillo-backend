package com.sudo.raillo.booking.redis;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisKeyExpiredEvent;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCleanupListener {

	private final RedisTemplate<String, Object> objectRedisTemplate;

	@EventListener
	public void handleRedisKeyExpiredEvent(RedisKeyExpiredEvent<Object> event) {
		// 1. 만료된 키 가져오기
		String expiredKey = new String(event.getSource(), StandardCharsets.UTF_8);

		validateReservationKey(expiredKey);

		// 2. 만료된 키와 관련된 모든 인덱스 키 패턴 생성
		String collectionKeyPattern = expiredKey + ":*";

		// 3. 패턴에 해당하는 모든 관련 키를 Redis에서 조회
		Set<String> keysToDelete = objectRedisTemplate.keys(collectionKeyPattern);

		// 4. 찾은 컬렉션 키들을 모두 삭제
		if (!keysToDelete.isEmpty()) {
			Long deletedCount = objectRedisTemplate.delete(keysToDelete);
			log.info("TTL 만료. 주 키: [{}]. 관련 컬렉션 키 {}개 삭제 완료.", expiredKey, deletedCount);
		}
	}

	private void validateReservationKey(String expiredKey) {
		if (!expiredKey.startsWith("reservation:") && !expiredKey.startsWith("seat-reservation:")) {return;}
	}
}
