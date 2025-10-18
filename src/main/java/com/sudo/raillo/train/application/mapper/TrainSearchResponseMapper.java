package com.sudo.raillo.train.application.mapper;

import org.springframework.stereotype.Component;

import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.response.SeatTypeInfo;
import com.sudo.raillo.train.application.dto.response.StandingTypeInfo;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.domain.StationFare;

import lombok.extern.slf4j.Slf4j;

/**
 * 열차 검색 응답 Mapper
 * 책임: TrainSearchResponse 생성 로직
 * (기존 TrainSearchApplicationService의 createTrainSearchResponse, createStandingInfoIfNeeded 로직)
 */
@Slf4j
@Component
public class TrainSearchResponseMapper {

	private static final double STANDING_FARE_DISCOUNT_RATE = 0.15;

	/**
	 * 열차 검색 응답 생성
	 */
	public TrainSearchResponse toResponse(
		TrainBasicInfo trainInfo,
		SectionSeatStatus sectionStatus,
		StationFare fare,
		int passengerCount,
		double standingRatio) {
		// 입석 가능 여부 판단
		boolean hasStandingForStandard = !sectionStatus.canReserveStandard()
			&& sectionStatus.canReserveStanding(passengerCount, standingRatio);

		// 1. 좌석 타입별 정보 생성 (일반실 / 특실)
		SeatTypeInfo standardSeatInfo = SeatTypeInfo.create(
			sectionStatus.standardRemaining(),
			sectionStatus.standardTotal(),
			fare.getStandardFare(),
			passengerCount,
			"일반실",
			hasStandingForStandard,
			sectionStatus.canReserveStandard()
		);

		SeatTypeInfo firstClassSeatInfo = SeatTypeInfo.create(
			sectionStatus.firstClassRemaining(),
			sectionStatus.firstClassTotal(),
			fare.getFirstClassFare(),
			passengerCount,
			"특실",
			false,
			sectionStatus.canReserveFirstClass()
		);

		// 2. 입석 정보 생성 (필요시)
		StandingTypeInfo standingInfo = createStandingInfoIfNeeded(
			sectionStatus, fare, passengerCount, standingRatio);

		return TrainSearchResponse.of(
			trainInfo.trainScheduleId(),
			String.format("%03d", trainInfo.trainNumber()),
			trainInfo.trainName(),
			trainInfo.departureStationName(),
			trainInfo.arrivalStationName(),
			trainInfo.departureTime(),
			trainInfo.arrivalTime(),
			standardSeatInfo,
			firstClassSeatInfo,
			standingInfo
		);
	}

	/**
	 * 입석 정보 생성 (필요한 경우만)
	 */
	private StandingTypeInfo createStandingInfoIfNeeded(
		SectionSeatStatus sectionStatus,
		StationFare fare,
		int passengerCount,
		double standingRatio) {
		// 일반실 예약 불가 && (입석 잔여석 > 승객 수) 인 경우만
		boolean shouldShowStanding = !sectionStatus.canReserveStandard()
			&& sectionStatus.canReserveStanding(passengerCount, standingRatio);

		if (!shouldShowStanding) {
			return null;
		}

		int standingFare = (int)(fare.getStandardFare() * (1.0 - STANDING_FARE_DISCOUNT_RATE));

		return StandingTypeInfo.create(
			sectionStatus.getStandingRemaining(standingRatio),
			sectionStatus.getMaxStandingCapacity(standingRatio),
			standingFare
		);
	}
}
