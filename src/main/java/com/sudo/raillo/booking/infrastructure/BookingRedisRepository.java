package com.sudo.raillo.booking.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.global.redis.exception.RedisError;
import com.sudo.raillo.global.redis.exception.RedisException;
import com.sudo.raillo.global.redis.util.RedisKeyGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class BookingRedisRepository {

	private final RedisTemplate<String, Object> customObjectRedisTemplate;
	private final RedisKeyGenerator redisKeyGenerator;

	@Value("${redis.ttl.pending-booking}")
	private Duration pendingBookingExpireTime;
	@Value("${redis.ttl.pending-booking-member-key}")
	private Duration pendingBookingMemberKeyExpireTime;

	public Duration getPendingBookingExpireTime() {
		return pendingBookingExpireTime;
	}

	public void savePendingBooking(PendingBooking pendingBooking) {
		savePendingBooking(pendingBooking, pendingBookingExpireTime);
	}

	public void savePendingBooking(PendingBooking pendingBooking, Duration pendingBookingTtl) {
		Duration normalizedPendingBookingTtl = normalizeTtl(pendingBookingTtl);
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBooking.getId());
		customObjectRedisTemplate.opsForValue()
			.set(key, pendingBooking, normalizedPendingBookingTtl);
		// PendingBookingMemberKey는 항상 같이 저장
		savePendingBookingMemberKey(pendingBooking.getId(), pendingBooking.getMemberNo(), normalizedPendingBookingTtl);
	}

	public void deletePendingBooking(String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBookingId);
		customObjectRedisTemplate.delete(key);
	}

	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		List<String> pendingBookingKeys = pendingBookingIds.stream()
			.map(redisKeyGenerator::generatePendingBookingKey)
			.toList();

		List<String> pendingBookingMemberKeys = pendingBookingIds.stream()
			.map(id -> redisKeyGenerator.generatePendingBookingMemberKey(memberNo, id))
			.toList();

		List<String> allKeysToDelete = new ArrayList<>();
		allKeysToDelete.addAll(pendingBookingKeys);
		allKeysToDelete.addAll(pendingBookingMemberKeys);

		customObjectRedisTemplate.delete(allKeysToDelete);
	}

	public Optional<PendingBooking> getPendingBooking(String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBookingId);
		return Optional.ofNullable(customObjectRedisTemplate.opsForValue().get(key))
			.map(PendingBooking.class::cast);
	}

	public Map<String, PendingBooking> getPendingBookingsAsMap(List<String> pendingBookingIds) {
		List<String> keys = pendingBookingIds.stream()
			.map(redisKeyGenerator::generatePendingBookingKey)
			.toList();

		List<Object> results = customObjectRedisTemplate.opsForValue().multiGet(keys);

		if (results == null) {
			log.error("[Redis MGET 실행 실패] Redis 연결 또는 실행 오류");
			throw new RedisException(RedisError.MGET_OPERATION_FAIL);
		}

		return IntStream.range(0, results.size())
			.filter(i -> results.get(i) != null)
			.boxed()
			.collect(Collectors.toMap(pendingBookingIds::get, i -> (PendingBooking)results.get(i)));
	}

	public void deletePendingBookingMemberKey(String memberNo, String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingMemberKey(memberNo, pendingBookingId);
		customObjectRedisTemplate.delete(key);
	}

	public List<PendingBooking> getPendingBookings(String memberNo) {
		return getPendingBookingMemberKeys(memberNo).stream()
			.map(this::extractPendingBookingId)
			.map(this::getPendingBooking)
			.flatMap(Optional::stream)
			.collect(Collectors.toList());
	}

	private String extractPendingBookingId(String memberKey) {
		return memberKey.substring(memberKey.lastIndexOf(":") + 1);
	}

	private Set<String> getPendingBookingMemberKeys(String memberNo) {
		String key = redisKeyGenerator.generatePendingBookingMemberKeyPattern(memberNo);

		Set<String> memberKeys = new HashSet<>();
		ScanOptions options = ScanOptions.scanOptions()
			.match(key)
			.count(10) // 10개씩 조회
			.build();

		try (Cursor<String> cursor = customObjectRedisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				memberKeys.add(cursor.next());
			}
		} catch (Exception e) {
			log.error("레디스 예약 조회 실패: memberNo={}, error={}", memberNo, e.getMessage());
			throw new RedisException(RedisError.SCAN_OPERATION_FAIL);
		}

		return memberKeys;
	}

	private void savePendingBookingMemberKey(String pendingBookingId, String memberNo, Duration pendingBookingTtl) {
		String key = redisKeyGenerator.generatePendingBookingMemberKey(memberNo, pendingBookingId);
		customObjectRedisTemplate.opsForValue()
			.set(key, "1", resolvePendingBookingMemberKeyTtl(pendingBookingTtl)); // 임시 더미값 저장
	}

	private Duration resolvePendingBookingMemberKeyTtl(Duration pendingBookingTtl) {
		return pendingBookingMemberKeyExpireTime.compareTo(pendingBookingTtl) < 0
			? pendingBookingMemberKeyExpireTime
			: pendingBookingTtl;
	}

	private Duration normalizeTtl(Duration ttl) {
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			log.warn("[PendingBooking TTL 비정상] ttl={}, 1초로 보정합니다.", ttl);
			return Duration.ofSeconds(1);
		}
		return ttl;
	}
}
