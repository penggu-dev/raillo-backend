package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.global.redis.exception.RedisError;
import com.sudo.raillo.global.redis.exception.RedisException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisHashCommands.HashFieldSetOption;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Repository;

/**
 * 예약 본문과 회원별 예약 인덱스를 다룬다. 예약 본문의 생성은 점유 검사와 함께 {@link SeatOccupancyRepository}가 맡는다.
 *
 * <p>회원 인덱스는 {@code member:{memberNo}:reservations} Hash이며 field는 예약 ID, 값은 운행 ID다.
 * field마다 예약과 같은 TTL이 걸리므로 만료된 예약은 인덱스에서도 사라진다.</p>
 */
@Repository
@RequiredArgsConstructor
public class ReservationRedisRepository {

	private final StringRedisTemplate stringRedisTemplate;
	private final RedisJsonConverter redisJsonConverter;

	/**
	 * 회원 인덱스에 예약을 등록한다. HSETEX 한 명령으로 값과 field 만료를 함께 건다.
	 */
	public void indexForMember(String memberNo, String reservationId, long trainScheduleId, Duration ttl) {
		stringRedisTemplate.opsForHash().putAndExpire(
			ReservationCacheKey.memberReservations(memberNo),
			Map.of(reservationId, String.valueOf(trainScheduleId)),
			HashFieldSetOption.upsert(),
			Expiration.from(ttl)
		);
	}

	public void removeMemberIndex(String memberNo, String reservationId) {
		stringRedisTemplate.opsForHash().delete(ReservationCacheKey.memberReservations(memberNo), reservationId);
	}

	/**
	 * 회원 인덱스에서 예약 ID별 운행 ID를 찾는다. 인덱스에 없는 예약은 결과에서 빠진다.
	 */
	public Map<String, Long> findScheduleIds(String memberNo, List<String> reservationIds) {
		List<Object> values = stringRedisTemplate.opsForHash()
			.multiGet(ReservationCacheKey.memberReservations(memberNo), List.copyOf(reservationIds));

		Map<String, Long> scheduleIds = new LinkedHashMap<>();
		for (int i = 0; i < reservationIds.size(); i++) {
			Object value = values.get(i);
			if (value != null) {
				scheduleIds.put(reservationIds.get(i), Long.parseLong((String)value));
			}
		}
		return scheduleIds;
	}

	/** 예약 본문 키가 있는지 확인한다. 점유와 본문은 한 스크립트에서 저장되므로 점유 저장 여부로도 쓴다. */
	public boolean exists(long trainScheduleId, String reservationId) {
		return Boolean.TRUE.equals(
			stringRedisTemplate.hasKey(ReservationCacheKey.reservation(trainScheduleId, reservationId)));
	}

	public Optional<Reservation> find(long trainScheduleId, String reservationId) {
		String json = stringRedisTemplate.opsForValue()
			.get(ReservationCacheKey.reservation(trainScheduleId, reservationId));
		return Optional.ofNullable(json).map(value -> redisJsonConverter.fromJson(value, Reservation.class));
	}

	/**
	 * 예약 본문을 한 번에 읽는다. 만료돼 없는 예약은 결과에서 빠진다.
	 *
	 * @param scheduleIdByReservationId 예약 ID별 운행 ID. {@link #findScheduleIds} 결과를 그대로 넘긴다
	 */
	public Map<String, Reservation> findAll(Map<String, Long> scheduleIdByReservationId) {
		if (scheduleIdByReservationId.isEmpty()) {
			return Map.of();
		}
		List<String> reservationIds = List.copyOf(scheduleIdByReservationId.keySet());
		List<String> keys = reservationIds.stream()
			.map(id -> ReservationCacheKey.reservation(scheduleIdByReservationId.get(id), id))
			.toList();

		List<String> jsons = stringRedisTemplate.opsForValue().multiGet(keys);
		if (jsons == null) {
			throw new RedisException(RedisError.MGET_OPERATION_FAIL);
		}

		Map<String, Reservation> reservations = new LinkedHashMap<>();
		for (int i = 0; i < reservationIds.size(); i++) {
			if (jsons.get(i) != null) {
				reservations.put(reservationIds.get(i), redisJsonConverter.fromJson(jsons.get(i), Reservation.class));
			}
		}
		return reservations;
	}
}
