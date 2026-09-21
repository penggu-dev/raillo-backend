package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.application.dto.SeatOccupancyQuery;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.cache.SeatOccupancyValue;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 검색 구간의 R/B 좌석을 읽는다. field 만료는 Redis가 처리하며 같은 좌석의 여러 구간은 한 번만 센다. */
@Repository
@RequiredArgsConstructor
public class SeatOccupancyQueryRepository {
	private final StringRedisTemplate redis;

	public Map<Long, Set<Long>> findOccupiedSeatIds(SeatOccupancyQuery query) {
		if (query.carIds().isEmpty()) return Map.of();
		var results = redis.executePipelined((RedisCallback<Object>) connection -> {
			for (long carId : query.carIds()) {
				connection.hashCommands().hGetAll(redis.getStringSerializer()
					.serialize(ReservationCacheKey.carSeats(query.scheduleId(), carId)));
			}
			return null;
		});
		Map<Long, Set<Long>> occupied = new HashMap<>();
		for (int i = 0; i < query.carIds().size(); i++) {
			Map<?, ?> fields = (Map<?, ?>) results.get(i);
			Set<Long> seats = new HashSet<>();
			for (var entry : fields.entrySet()) {
				String[] field = entry.getKey().toString().split(":");
				if (field.length != 2) throw new IllegalStateException("잘못된 좌석 field: " + entry.getKey());
				int section = Integer.parseInt(field[1]);
				if (section >= query.departureStopOrder() && section < query.arrivalStopOrder()) {
					SeatOccupancyValue.parse(entry.getValue().toString());
					seats.add(Long.parseLong(field[0]));
				}
			}
			occupied.put(query.carIds().get(i), Set.copyOf(seats));
		}
		return occupied;
	}
}
