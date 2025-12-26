package com.sudo.raillo.booking.application.facade;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.service.FareCalculationService;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PendingBookingFacade {

	private final PendingBookingService pendingBookingService;
	private final FareCalculationService fareCalculationService;
	private final TrainSeatQueryService trainSeatQueryService;

	public PendingBookingCreateResponse createPendingBooking(PendingBookingCreateRequest request, String memberNo) {
		// 모든 좌석의 객차타입이 일치하는지 검증
		List<CarType> carTypes = trainSeatQueryService.getCarTypes(request.seatIds());
		CarType carType = validateSeatIdsAndGetSingleCarType(carTypes);
		log.debug("[좌석 검증 통과] seatIds={}", request.seatIds());

		BigDecimal totalFare = fareCalculationService.calculateTotalFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengerTypes(),
			carType
		);

		// TODO: 좌석락 로직 필요

		PendingBooking pendingBooking = pendingBookingService.createPendingBooking(request, memberNo, totalFare);

		return new PendingBookingCreateResponse(pendingBooking.getId());
	}

	/**
	 * 좌석 검증
	 * 1. 좌석 존재 여부 검증
	 * 2. 동일 객차 타입 검증
	 */
	private CarType validateSeatIdsAndGetSingleCarType(List<CarType> carTypes) {
		if (carTypes.isEmpty()) {
			log.warn("[좌석 조회 실패] 요청한 좌석 ID에 해당하는 좌석이 없음");
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		if (carTypes.size() != 1) {
			log.warn("[객차 타입 불일치] 서로 다른 객차 타입이 섞여 있음: carTypes={}", carTypes);
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.get(0);
	}
}
