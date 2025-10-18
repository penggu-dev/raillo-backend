package com.sudo.raillo.train.infrastructure;

import com.sudo.raillo.train.application.dto.TrainCarSeatInfo;

/**
 * 좌석 조회 Repository
 */
public interface SeatRepositoryCustom {

	/**
	 * 열차 객차 좌석 상세 조회
	 */
	TrainCarSeatInfo findTrainCarSeatDetail(
		Long trainCarId,
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId
	);
}
