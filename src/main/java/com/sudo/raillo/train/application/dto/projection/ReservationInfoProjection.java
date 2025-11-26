package com.sudo.raillo.train.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.Getter;

@Getter
public class ReservationInfoProjection {

	private final Long trainScheduleId;
	private final Long seatId;
	private final CarType carType;
	private final Long departureStationId;
	private final Long arrivalStationId;

	@QueryProjection
	public ReservationInfoProjection(Long trainScheduleId, Long seatId, CarType carType,
		Long departureStationId, Long arrivalStationId) {
		this.trainScheduleId = trainScheduleId;
		this.seatId = seatId;
		this.carType = carType;
		this.departureStationId = departureStationId;
		this.arrivalStationId = arrivalStationId;
	}
}
