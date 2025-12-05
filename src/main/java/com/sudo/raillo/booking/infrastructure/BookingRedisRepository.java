package com.sudo.raillo.booking.infrastructure;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.global.redis.exception.RedisError;
import com.sudo.raillo.global.redis.exception.RedisException;
import com.sudo.raillo.global.redis.util.RedisKeyGenerator;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class BookingRedisRepository {

	private final RedisTemplate<String, Object> objectRedisTemplate;
	private final RedisKeyGenerator redisKeyGenerator;

	private static final Duration PENDING_BOOKING_EXPIRE_TIME = Duration.ofMinutes(10); // TODO: 설정 파일로 분리
	private static final Duration PENDING_BOOKING_MEMBER_EXPIRE_TIME = Duration.ofSeconds(580);

	public void savePendingBooking(PendingBooking pendingBooking) {
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBooking.getId());
		objectRedisTemplate.opsForValue()
			.set(key, pendingBooking, PENDING_BOOKING_EXPIRE_TIME);
	}

	public void deletePendingBooking(String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBookingId);
		objectRedisTemplate.delete(key);
	}

	public PendingBooking getPendingBooking(String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingKey(pendingBookingId);
		Object value = objectRedisTemplate.opsForValue().get(key);

		if(value == null) {
			return null;
		}

		return (PendingBooking)value;
	}

	public void savePendingBookingMemberKey(String pendingBookingId, String memberNo) {
		String key = redisKeyGenerator.generatePendingBookingMemberKey(memberNo, pendingBookingId);
		objectRedisTemplate.opsForValue()
			.set(key, "1", PENDING_BOOKING_MEMBER_EXPIRE_TIME); // 임시 더미값 저장
	}

	public void deletePendingBookingMemberKey(String memberNo, String pendingBookingId) {
		String key = redisKeyGenerator.generatePendingBookingMemberKey(memberNo, pendingBookingId);
		objectRedisTemplate.delete(key);
	}

	public List<PendingBooking> getPendingBookings(String memberNo) {
		return getPendingBookingMemberKeys(memberNo).stream()
			.map(this::extractPendingBookingId)
			.map(this::getPendingBooking)
			.filter(Objects::nonNull)
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

		try (Cursor<String> cursor = objectRedisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				memberKeys.add(cursor.next());
			}
		} catch (Exception e) {
			throw new RedisException(RedisError.SCAN_OPERATION_FAIL);
		}

		return memberKeys;
	}
}

