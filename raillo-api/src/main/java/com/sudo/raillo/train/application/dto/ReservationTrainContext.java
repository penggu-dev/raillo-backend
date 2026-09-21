package com.sudo.raillo.train.application.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;

/** 예약 생성에 쓰는 기준정보. 구간 운임은 캐시에 없으면 null이며 구간 검증 뒤에 확인한다. */
public record ReservationTrainContext(
	ScheduleInfoCacheValue schedule,
	ScheduleStopCacheValue departureStop,
	ScheduleStopCacheValue arrivalStop,
	Map<Long, SeatCacheValue> seatsById,
	StationFareCacheValue fare
) {

	/**
	 * 출발 정차역의 출발 일시. 정차역 출발 시각이 열차 출발 시각보다 이르면 자정을 넘긴 것이므로 다음 날이다.
	 */
	public LocalDateTime departureAt() {
		return departureStop.departureTime().isBefore(schedule.departureTime())
			? schedule.operationDate().plusDays(1).atTime(departureStop.departureTime())
			: schedule.operationDate().atTime(departureStop.departureTime());
	}
}
