package com.sudo.raillo.train.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.sudo.raillo.train.domain.TrainSchedule;

/**
 * 열차 스케줄 시간 정보 DTO (캐시용)
 *
 * <p>Sold TTL 계산에 필요한 최소 정보만 포함</p>
 */
public record TrainScheduleTimeInfo(
	Long id,
	LocalDate operationDate,
	LocalTime departureTime,
	LocalTime arrivalTime
) {
	public static TrainScheduleTimeInfo from(TrainSchedule trainSchedule) {
		return new TrainScheduleTimeInfo(
			trainSchedule.getId(),
			trainSchedule.getOperationDate(),
			trainSchedule.getDepartureTime(),
			trainSchedule.getArrivalTime()
		);
	}
}
