package com.sudo.raillo.train.application.mapper;

import org.springframework.stereotype.Component;

import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.response.SeatTypeInfo;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.domain.StationFare;

import lombok.extern.slf4j.Slf4j;

/**
 * 열차 검색 응답 Mapper
 * 책임: TrainSearchResponse 생성 로직
 * (기존 TrainSearchApplicationService의 createTrainSearchResponse 로직)
 */
@Slf4j
@Component
public class TrainSearchResponseMapper {

	/**
	 * 열차 검색 응답 생성
	 */
	public TrainSearchResponse toResponse(
		TrainBasicInfo trainInfo,
		SectionSeatStatus sectionStatus,
		StationFare fare,
		int passengerCount) {

		// 1. 좌석 타입별 정보 생성 (일반실 / 특실)
		SeatTypeInfo standardSeatInfo = SeatTypeInfo.create(
			sectionStatus.standardRemaining(),
			sectionStatus.standardTotal(),
			fare.getStandardFare(),
			passengerCount,
			"일반실",
			sectionStatus.canReserveStandard()
		);

		SeatTypeInfo firstClassSeatInfo = SeatTypeInfo.create(
			sectionStatus.firstClassRemaining(),
			sectionStatus.firstClassTotal(),
			fare.getFirstClassFare(),
			passengerCount,
			"특실",
			sectionStatus.canReserveFirstClass()
		);

		return TrainSearchResponse.of(
			trainInfo.trainScheduleId(),
			String.format("%03d", trainInfo.trainNumber()),
			trainInfo.trainName(),
			trainInfo.departureStationName(),
			trainInfo.arrivalStationName(),
			trainInfo.departureTime(),
			trainInfo.arrivalTime(),
			standardSeatInfo,
			firstClassSeatInfo
		);
	}

}
