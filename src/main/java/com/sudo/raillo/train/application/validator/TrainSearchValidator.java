package com.sudo.raillo.train.application.validator;

import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.StationRepository;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrainSearchValidator {

	private final TrainScheduleRepository trainScheduleRepository;
	private final TrainCarRepository trainCarRepository;
	private final StationRepository stationRepository;

	/**
	 * 열차 스케줄 검색 요청 검증
	 * - 출발역/도착역 동일 여부
	 * - 운행 날짜 범위
	 * - 출발 시간 유효성
	 */
	public void validateScheduleSearchRequest(TrainSearchRequest request) {
		validateOperationDateRange(request);
		validateStationsExistAndNotSame(request.departureStationId(), request.arrivalStationId());
		validateDepartureTimeNotPassed(request);
	}

	/**
	 * 객차 목록 조회 요청 검증
	 * - 열차 스케줄 존재 여부
	 * - 출발역/도착역 존재 및 동일하지 않은지 확인
	 */
	public void validateTrainCarListRequest(TrainCarListRequest request) {
		validateTrainScheduleExists(request.trainScheduleId());
		validateStationsExistAndNotSame(request.departureStationId(), request.arrivalStationId());
	}

	/**
	 * 좌석 상세 조회 요청 검증
	 * - 객차 및 열차 스케줄 존재 여부
	 * - 출발역/도착역 존재 및 동일하지 않은지 확인
	 */
	public void validateTrainCarSeatDetailRequest(TrainCarSeatDetailRequest request) {
		validateTrainCarExists(request.trainCarId());
		validateTrainScheduleExists(request.trainScheduleId());
		validateStationsExistAndNotSame(request.departureStationId(), request.arrivalStationId());
	}

	// ===== 비즈니스 규칙 검증 =====

	/**
	 * 출발역과 도착역이 동일하지 않은지 검증
	 */
	private void validateDepartureAndArrivalStationNotSame(Long departureStationId, Long arrivalStationId) {
		if (departureStationId.equals(arrivalStationId)) {
			log.warn("출발역과 도착역이 동일함: stationId={}", departureStationId);
			throw new BusinessException(TrainErrorCode.INVALID_ROUTE);
		}
	}

	/**
	 * 운행 날짜 범위 검증 (1개월 이내)
	 */
	private void validateOperationDateRange(TrainSearchRequest request) {
		if (request.operationDate().isAfter(LocalDate.now().plusMonths(1))) {
			throw new BusinessException(TrainErrorCode.OPERATION_DATE_TOO_FAR);
		}
	}

	/**
	 * 출발 시간이 과거가 아닌지 검증
	 * - 오늘 날짜인 경우에만 시간 비교
	 */
	private void validateDepartureTimeNotPassed(TrainSearchRequest request) {
		if (request.operationDate().equals(LocalDate.now())) {
			int requestHour = Integer.parseInt(request.departureHour());
			int currentHour = LocalTime.now().getHour();

			if (requestHour < currentHour) {
				throw new BusinessException(TrainErrorCode.DEPARTURE_TIME_PASSED);
			}
		}
	}

	// ===== 엔티티 존재 검증 =====

	/**
	 * 열차 스케줄 존재 검증
	 */
	private void validateTrainScheduleExists(Long trainScheduleId) {
		if (!trainScheduleRepository.existsById(trainScheduleId)) {
			log.warn("존재하지 않는 열차 스케줄: trainScheduleId={}", trainScheduleId);
			throw new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND);
		}
	}

	/**
	 * 객차 존재 검증
	 */
	private void validateTrainCarExists(Long trainCarId) {
		if (!trainCarRepository.existsById(trainCarId)) {
			log.warn("존재하지 않는 객차: trainCarId={}", trainCarId);
			throw new BusinessException(TrainErrorCode.TRAIN_CAR_NOT_FOUND);
		}
	}

	/**
	 * 역 존재 검증
	 */
	private void validateStationExists(Long stationId, String stationType) {
		if (!stationRepository.existsById(stationId)) {
			log.warn("존재하지 않는 {}: stationId={}", stationType, stationId);
			throw new BusinessException(TrainErrorCode.STATION_NOT_FOUND);
		}
	}

	/**
	 * 역 존재 및 출발역/도착역 동일하지 않은지 검증
	 * - 출발역 존재 확인
	 * - 도착역 존재 확인
	 * - 출발역 != 도착역 확인
	 */
	private void validateStationsExistAndNotSame(Long departureStationId, Long arrivalStationId) {
		validateStationExists(departureStationId, "출발역");
		validateStationExists(arrivalStationId, "도착역");
		validateDepartureAndArrivalStationNotSame(departureStationId, arrivalStationId);
	}
}
