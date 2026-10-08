package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.application.dto.SeatOccupancyQuery;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.cache.SeatOccupancyValue;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 검색 구간의 R/B 좌석을 읽는다. field 만료는 Redis가 처리하며 같은 좌석의 여러 구간은 한 번만 센다.
 *
 * <p>field 이름이나 값이 계약과 다르면 {@code SEAT_OCCUPANCY_CORRUPTED}로 올린다. 이 읽기 경로가 오염을 가장 먼저
 * 만나는 곳이라 분류를 틀리면 서버 데이터 문제가 호출자 잘못(400)으로 보고된다.</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SeatOccupancyQueryRepository {
	private final StringRedisTemplate redis;

	public Map<Long, Set<Long>> findOccupiedSeatIds(SeatOccupancyQuery query) {
		if (query.carIds().isEmpty()) return Map.of();

		var results = readCarSeatHashes(query);

		Map<Long, Set<Long>> occupied = new HashMap<>();
		for (int i = 0; i < query.carIds().size(); i++) {
			occupied.put(query.carIds().get(i), occupiedSeatsInRange((Map<?, ?>) results.get(i), query));
		}
		return occupied;
	}

	/** 객차별 점유 Hash를 파이프라인으로 한 번에 읽는다. 반환 순서는 {@code query.carIds()} 순서와 같다. */
	private List<Object> readCarSeatHashes(SeatOccupancyQuery query) {
		return redis.executePipelined((RedisCallback<Object>) connection -> {
			for (long carId : query.carIds()) {
				connection.hashCommands().hGetAll(redis.getStringSerializer()
					.serialize(ReservationCacheKey.carSeats(query.scheduleId(), carId)));
			}
			return null;
		});
	}

	/**
	 * 한 객차의 점유 Hash에서 검색 구간과 겹치는 좌석 ID를 모은다. 같은 좌석의 여러 구간은 Set이 한 번만 센다.
	 *
	 * <p>{@code Set.copyOf}로 불변 Set을 돌려준다. 호출자가 결과를 고칠 수 있으면 캐시 성격의 반환값이
	 * 조용히 바뀐다. 값 비교만 하는 테스트로는 드러나지 않으므로 여기서 지킨다.</p>
	 */
	private static Set<Long> occupiedSeatsInRange(Map<?, ?> fields, SeatOccupancyQuery query) {
		Set<Long> seats = new HashSet<>();
		for (var entry : fields.entrySet()) {
			String[] field = entry.getKey().toString().split(":");
			if (field.length != 2) {
				throw corrupted("좌석 field 형식이 아닙니다: " + entry.getKey(), null);
			}
			int section = parseSection(field[1], entry.getKey());
			if (section >= query.departureStopOrder() && section < query.arrivalStopOrder()) {
				validateValue(entry.getKey(), entry.getValue());
				seats.add(parseSeatId(field[0], entry.getKey()));
			}
		}
		return Set.copyOf(seats);
	}

	private static int parseSection(String value, Object field) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw corrupted("좌석 field의 구간이 숫자가 아닙니다: " + field, e);
		}
	}

	private static long parseSeatId(String value, Object field) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw corrupted("좌석 field의 좌석 ID가 숫자가 아닙니다: " + field, e);
		}
	}

	private static void validateValue(Object field, Object value) {
		try {
			SeatOccupancyValue.parse(value.toString());
		} catch (IllegalArgumentException e) {
			throw corrupted("좌석 점유 값이 아닙니다: field=" + field + ", value=" + value, e);
		}
	}

	private static BusinessException corrupted(String detail, Throwable cause) {
		log.error("[좌석 점유 데이터 오염] {}", detail, cause);
		return new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED);
	}
}
