package com.sudo.raillo.train.application.dto.projection;

import java.util.List;
import java.util.Map;

import com.sudo.raillo.train.domain.type.CarType;

/**
 * 스케줄별 CarType별 객차 ID 배치 조회 결과
 */
public class TrainCarIdsBatch {

	private final Map<Long, Map<CarType, List<Long>>> trainCarIdsBySchedule;

	public TrainCarIdsBatch(Map<Long, Map<CarType, List<Long>>> trainCarIdsBySchedule) {
		this.trainCarIdsBySchedule = trainCarIdsBySchedule;
	}

	public List<Long> getTrainCarIds(Long trainScheduleId, CarType carType) {
		return trainCarIdsBySchedule
			.getOrDefault(trainScheduleId, Map.of())
			.getOrDefault(carType, List.of());
	}
}
