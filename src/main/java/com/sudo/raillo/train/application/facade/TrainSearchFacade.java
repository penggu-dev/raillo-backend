package com.sudo.raillo.train.application.facade;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.service.CarRecommendationService;
import com.sudo.raillo.train.application.service.TrainSearchService;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.application.calculator.SeatAvailabilityCalculator;
import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.TrainScheduleBasicInfo;
import com.sudo.raillo.train.application.dto.projection.TrainSeatInfoBatch;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.application.mapper.TrainSearchResponseMapper;
import com.sudo.raillo.train.application.service.TrainCalendarService;
import com.sudo.raillo.train.application.validator.TrainSearchValidator;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 열차 검색 ApplicationService - Facade 패턴
 *
 * 책임:
 * 1. 비즈니스 플로우 조율
 * 2. 여러 서비스 조합
 * 3. 객차 추천 등 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainSearchFacade {

	private final TrainSearchValidator trainSearchValidator;
	private final TrainCalendarService trainCalendarService;
	private final CarRecommendationService carRecommendationService;
	private final TrainSearchService trainSearchService;
	private final TrainSeatQueryService trainSeatQueryService;
	private final SeatAvailabilityCalculator seatAvailabilityCalculator;
	private final TrainSearchResponseMapper responseMapper;

	/**
	 * 운행 캘린더 조회
	 * 금일로부터 한달간의 운행 스케줄 캘린더를 조회
	 */
	public List<OperationCalendarItemResponse> getOperationCalendar() {
		return trainCalendarService.getOperationCalendar();
	}

	/**
	 * 통합 열차 조회 (열차 스케줄 검색)
	 * 출발역, 도착역, 운행 날짜로 열차 스케줄 검색
	 */
	public TrainSearchSlicePageResponse searchTrains(TrainSearchRequest request, Pageable pageable) {
		// 1. 비즈니스 검증
		trainSearchValidator.validateScheduleSearchRequest(request); // TODO : 의미있는 네이밍 명으로 변경 필요

		// 2. 기본 열차 정보 조회
		Slice<TrainBasicInfo> trainInfoSlice = trainSearchService.findTrainBasicInfo(request, pageable);

		// 3. 빈 결과 처리
		if (trainInfoSlice.isEmpty()) {
			log.info("열차 조회 결과 없음: {}역 -> {}역, {}, {}시 이후 - 빈 결과 반환",
				request.departureStationId(), request.arrivalStationId(),
				request.operationDate(), request.departureHour());
			return TrainSearchSlicePageResponse.empty(pageable);
		}

		// 4. 구간별 요금 정보 조회
		StationFare fare = trainSearchService.findStationFare(
			request.departureStationId(), request.arrivalStationId());

		// 5. 각 열차별 좌석 상태 계산 및 응답 생성
		List<TrainSearchResponse> trainSearchResults = processTrainSearchResults(trainInfoSlice.getContent(), fare, request);

		log.info("Slice 기반 열차 조회: {}건 조회, hasNext: {}",
			trainSearchResults.size(), trainInfoSlice.hasNext());

		return TrainSearchSlicePageResponse.of(trainSearchResults, trainInfoSlice);
	}

	/**
	 * 열차 객차 목록 조회 (잔여 좌석이 있는 객차만)
	 * 선택한 열차의 잔여 좌석이 있는 객차 목록을 조회하고, 추천 객차를 선정
	 */
	public TrainCarListResponse getAvailableTrainCars(TrainCarListRequest request) {
		// 1. 비즈니스 검증
		trainSearchValidator.validateTrainCarListRequest(request);

		// 2. 열차 스케줄 기본 정보 조회
		TrainScheduleBasicInfo scheduleInfo = trainSearchService.getTrainScheduleBasicInfo(request.trainScheduleId());

		// 3. 잔여 좌석이 있는 객차 목록 조회
		List<TrainCarInfo> availableCars = trainSeatQueryService.getAvailableTrainCars(
			request.trainScheduleId(), request.departureStationId(), request.arrivalStationId());

		// 4. 승객 수에 맞는 추천 객차 선택 (Application Service 책임)
		String recommendedCarNumber = carRecommendationService.selectRecommendedCar(availableCars, request.passengerCount());

		log.info("열차 객차 목록 조회 완료: {}개 객차, 추천 객차={}, 열차={}-{}",
			availableCars.size(), recommendedCarNumber,
			scheduleInfo.trainClassificationCode(), scheduleInfo.trainNumber());

		return TrainCarListResponse.of(
			request.trainScheduleId(),
			recommendedCarNumber,
			availableCars.size(),
			scheduleInfo.trainClassificationCode(),
			scheduleInfo.trainNumber(),
			availableCars
		);
	}

	/**
	 * 열차 객차 좌석 상세 조회
	 */
	public TrainCarSeatDetailResponse getTrainCarSeatDetail(TrainCarSeatDetailRequest request) {
		trainSearchValidator.validateTrainCarSeatDetailRequest(request);

		log.info("열차 객차 좌석 상세 조회: trainCarId={}, trainScheduleId={}, {}역 -> {}역",
			request.trainCarId(), request.trainScheduleId(),
			request.departureStationId(), request.arrivalStationId());

		return trainSeatQueryService.getTrainCarSeatDetail(request);
	}

	// ===== Private Helper Methods =====

	/**
	 * 열차 조회 결과 일괄 처리 (배치 쿼리 사용)
	 * 모든 열차의 데이터를 배치로 조회한 후 개별 처리
	 */
	private List<TrainSearchResponse> processTrainSearchResults(
		List<TrainBasicInfo> trainInfoSlice,
		StationFare fare,
		TrainSearchRequest request) {

		// 1. trainScheduleId 리스트 추출
		List<Long> trainScheduleIds = trainInfoSlice.stream()
			.map(TrainBasicInfo::trainScheduleId)
			.toList();

		log.info("배치 쿼리 시작: {}건의 열차 일괄 처리", trainScheduleIds.size());

		// 2. 배치 쿼리로 모든 데이터 한번에 조회
		TrainSeatInfoBatch seatInfoBatch = trainSearchService.findTrainSeatInfoBatch(trainScheduleIds);

		Map<Long, List<SeatBookingInfo>> overlappingBookingsMap =
			trainSearchService.findOverlappingBookingsBatch(
				trainScheduleIds, request.departureStationId(), request.arrivalStationId());

		// 3. 각 열차별로 배치 조회된 데이터를 사용해 응답 생성
		List<TrainSearchResponse> results = trainInfoSlice.stream()
			.map(trainInfo -> {
				try {
					return processTrainSearchResult(
						trainInfo, seatInfoBatch, overlappingBookingsMap, fare, request.passengerCount());
				} catch (Exception e) {
					log.warn("열차 {} 처리 실패: {}", trainInfo.trainNumber(), e.getMessage());
					return null;
				}
			})
			.filter(response -> response != null)
			.toList();

		if (results.isEmpty()) {
			log.warn("처리 가능한 열차 없음");
			throw new BusinessException(TrainErrorCode.NO_SEARCH_RESULTS);
		}

		log.info("배치 처리 완료: 전체 {}건 중 {}건 성공", trainInfoSlice.size(), results.size());
		return results;
	}

	/**
	 * 개별 열차 처리
	 */
	private TrainSearchResponse processTrainSearchResult(
		TrainBasicInfo trainInfo,
		TrainSeatInfoBatch seatInfoBatch,
		Map<Long, List<SeatBookingInfo>> overlappingBookingsMap,
		StationFare fare,
		int passengerCount) {

		Long trainScheduleId = trainInfo.trainScheduleId();

		// 배치 데이터 추출
		List<SeatBookingInfo> overlappingBookings =
			overlappingBookingsMap.getOrDefault(trainScheduleId, List.of());
		Map<CarType, Integer> totalSeatsByCarType =
			seatInfoBatch.getSeatsCountByCarType(trainScheduleId);

		// 좌석 상태 계산
		SectionSeatStatus sectionStatus = seatAvailabilityCalculator.calculateSectionSeatStatus(
			overlappingBookings, totalSeatsByCarType, passengerCount);

		// 응답 생성
		return responseMapper.toResponse(trainInfo, sectionStatus, fare, passengerCount);
	}
}
