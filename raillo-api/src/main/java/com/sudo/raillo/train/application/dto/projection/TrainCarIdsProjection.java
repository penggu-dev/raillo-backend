package com.sudo.raillo.train.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.train.domain.type.CarType;
import lombok.Getter;

@Getter
public class TrainCarIdsProjection {

	private final Long scheduleId;
	private final CarType carType;
	private final Long trainCarId;

	@QueryProjection
	public TrainCarIdsProjection(Long scheduleId, CarType carType, Long trainCarId) {
		this.scheduleId = scheduleId;
		this.carType = carType;
		this.trainCarId = trainCarId;
	}
}
