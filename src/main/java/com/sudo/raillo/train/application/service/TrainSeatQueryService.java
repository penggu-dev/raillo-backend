package com.sudo.raillo.train.application.service;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.TrainCarSeatInfo;
import com.sudo.raillo.train.application.dto.projection.SeatProjection;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.response.SeatDetail;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.SeatQueryRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainCarQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainSeatQueryService {

	private final TrainCarQueryRepository trainCarQueryRepository;
	private final SeatQueryRepository seatQueryRepository;
	private final SeatRepository seatRepository;

	/**
	 * 열차 객차 목록 조회 (잔여 좌석이 있는 객차만)
	 */
	public List<TrainCarInfo> getAvailableTrainCars(Long trainScheduleId, Long departureStationId,
		Long arrivalStationId) {
		// 1. 잔여 좌석이 있는 객차 목록 조회
		List<TrainCarInfo> availableCars = trainCarQueryRepository.findAvailableTrainCars(
			trainScheduleId, departureStationId, arrivalStationId);

		if (availableCars.isEmpty()) {
			log.warn("잔여 좌석이 있는 객차가 없음: trainScheduleId={}", trainScheduleId);
			throw new BusinessException(TrainErrorCode.NO_AVAILABLE_CARS);
		}

		return availableCars;
	}

	/**
	 * 열차 객차 좌석 상세 조회
	 */
	public TrainCarSeatDetailResponse getTrainCarSeatDetail(TrainCarSeatDetailRequest request) {
		// 1. 객차 좌석 상세 조회
		TrainCarSeatInfo carSeatInfo = seatQueryRepository.findTrainCarSeatDetail(
			request.trainCarId(),
			request.trainScheduleId(),
			request.departureStationId(),
			request.arrivalStationId()
		);

		// 2. 좌석 상세 정보 변환
		List<SeatDetail> seatDetails = carSeatInfo.seats().stream()
			.map(this::convertToSeatDetail)
			.toList();

		log.info("열차 객차 좌석 상세 조회 완료: 객차={}, 전체좌석={}, 잔여좌석={}",
			carSeatInfo.carNumber(), carSeatInfo.totalSeats(), carSeatInfo.remainingSeats());

		return new TrainCarSeatDetailResponse(
			carSeatInfo.carNumber(),
			carSeatInfo.carType(),
			carSeatInfo.totalSeats(),
			carSeatInfo.remainingSeats(),
			carSeatInfo.getLayoutType(),
			seatDetails
		);
	}

	/**
	 * 좌석 ID 목록에 해당하는 객차 타입 조회
	 * @return 중복 제거된 객차 타입 목록
	 */
	public List<CarType> getCarTypes(List<Long> seatIds) {
		return seatRepository.findCarTypes(seatIds);
	}

	// ===== Private Helper Methods =====

	/**
	 * SeatProjection -> SeatDetail 변환
	 */
	private SeatDetail convertToSeatDetail(SeatProjection seatProjection) {
		return new SeatDetail(
			seatProjection.getSeatId(),
			seatProjection.getSeatNumber(),
			seatProjection.isAvailable(),
			seatProjection.getSeatDirection(),
			seatProjection.getSeatType(),
			seatProjection.getSpecialMessage()
		);
	}
}
