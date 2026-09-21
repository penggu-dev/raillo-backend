package com.sudo.raillo.support.helper;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.cache.SeatOccupancyValue;

import lombok.RequiredArgsConstructor;

/**
 * 객차 좌석 점유 Hash에 값을 직접 기록한다. 예매 점유(B:)는 결제 확정 PR 전까지 이 헬퍼로만 만들 수 있다.
 */
@Component
@RequiredArgsConstructor
public class SeatOccupancyTestHelper {

	private final StringRedisTemplate stringRedisTemplate;

	/** 좌석의 [departure, arrival) 구간을 예매 점유로 기록한다. */
	public void markBooked(long trainScheduleId, long trainCarId, long seatId, int departureStopOrder, int arrivalStopOrder,
		String bookingId) {
		put(trainScheduleId, trainCarId, seatId, departureStopOrder, arrivalStopOrder,
			SeatOccupancyValue.booked(bookingId).serialize());
	}

	/** 좌석의 [departure, arrival) 구간을 예약 점유로 기록한다. 만료는 걸지 않는다. */
	public void markReserved(long trainScheduleId, long trainCarId, long seatId, int departureStopOrder, int arrivalStopOrder,
		String reservationId) {
		put(trainScheduleId, trainCarId, seatId, departureStopOrder, arrivalStopOrder,
			SeatOccupancyValue.reserved(reservationId).serialize());
	}

	public Map<Object, Object> entries(long trainScheduleId, long trainCarId) {
		return stringRedisTemplate.opsForHash().entries(ReservationCacheKey.carSeats(trainScheduleId, trainCarId));
	}

	public String valueOf(long trainScheduleId, long trainCarId, long seatId, int sectionIndex) {
		Object value = stringRedisTemplate.opsForHash()
			.get(ReservationCacheKey.carSeats(trainScheduleId, trainCarId), ReservationCacheKey.seatField(seatId, sectionIndex));
		return value == null ? null : value.toString();
	}

	private void put(long trainScheduleId, long trainCarId, long seatId, int departureStopOrder, int arrivalStopOrder,
		String value) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (int section : ReservationCacheKey.sectionIndices(departureStopOrder, arrivalStopOrder)) {
			fields.put(ReservationCacheKey.seatField(seatId, section), value);
		}
		stringRedisTemplate.opsForHash().putAll(ReservationCacheKey.carSeats(trainScheduleId, trainCarId), fields);
	}
}
