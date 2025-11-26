package com.sudo.raillo.train.application.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.TrainScheduleBasicInfo;
import com.sudo.raillo.train.application.dto.projection.TrainSeatInfoBatch;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.SeatBookingQueryRepository;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleQueryRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 열차 검색 Service
 * 책임: 데이터 조회, 공휴일 판단 등 도메인 로직, 좌석 계산 로직
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class TrainSearchService {

	private static final int SEAT_BUFFER_THRESHOLD = 20; // 여유석 판단 기준

	private final TrainScheduleRepository trainScheduleRepository;
	private final TrainScheduleQueryRepository trainScheduleQueryRepository;
	private final StationFareRepository stationFareRepository;
	private final SeatBookingQueryRepository seatBookingQueryRepository;

	/**
	 * 기본 열차 정보 조회
	 */
	public Slice<TrainBasicInfo> findTrainBasicInfo(TrainSearchRequest request, Pageable pageable) {
		LocalTime departureTimeFrom = request.getDepartureTimeFilter();

		Slice<TrainBasicInfo> trainPage = trainScheduleQueryRepository.findTrainBasicInfo(
			request.departureStationId(),
			request.arrivalStationId(),
			request.operationDate(),
			departureTimeFrom,
			pageable);

		return trainPage;
	}

	/**
	 * 구간별 요금 정보 조회
	 */
	public StationFare findStationFare(Long departureStationId, Long arrivalStationId) {
		log.debug("요금 정보 조회: {} -> {}", departureStationId, arrivalStationId);
		return stationFareRepository.findByDepartureStationIdAndArrivalStationId(departureStationId, arrivalStationId)
			.orElseThrow(() -> {
				log.error("요금 정보 없음: {} -> {}", departureStationId, arrivalStationId);
				return new BusinessException(TrainErrorCode.STATION_FARE_NOT_FOUND);
			});
	}

	/**
	 * 개별 열차 스케줄 기본 정보 조회
	 */
	public TrainScheduleBasicInfo getTrainScheduleBasicInfo(Long trainScheduleId) {
		TrainSchedule trainSchedule = trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_DETAIL_NOT_FOUND));

		return TrainScheduleBasicInfo.of(
			trainSchedule.getId(),
			trainSchedule.getTrain().getTrainType().getDescription(),
			String.format("%03d", trainSchedule.getTrain().getTrainNumber()),
			trainSchedule.getTrain().getTrainName()
		);
	}

	/**
	 * 열차 좌석 정보 배치 조회
	 */
	public TrainSeatInfoBatch findTrainSeatInfoBatch(List<Long> trainScheduleIds) {
		return trainScheduleQueryRepository.findTrainSeatInfoBatch(trainScheduleIds);
	}

	/**
	 * 겹치는 예약 배치 조회
	 */
	public Map<Long, List<SeatBookingInfo>> findOverlappingReservationsBatch(
		List<Long> trainScheduleIds, Long departureStationId, Long arrivalStationId) {
		return seatBookingQueryRepository.findOverlappingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId);
	}

	// ============================================
	// Service Layer 전용 내부 Records
	// ============================================

	public record SeatCalculationResult(
		int standardRemaining, int standardTotal,
		int firstClassRemaining, int firstClassTotal
	) {
	}
}
