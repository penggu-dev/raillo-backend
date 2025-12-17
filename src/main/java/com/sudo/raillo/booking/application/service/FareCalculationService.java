package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.StationFareRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FareCalculationService {

	private final StationFareRepository stationFareRepository;

	private static final Map<PassengerType, BigDecimal> DISCOUNT_RATES = Map.of(
		PassengerType.ADULT, BigDecimal.valueOf(1.0), // 정상가
		PassengerType.CHILD, BigDecimal.valueOf(0.6), // 10~40% 할인
		PassengerType.INFANT, BigDecimal.valueOf(0.25), // 좌석 지정 시 75% 할인, 좌석 지정 안하면 100% 할인
		PassengerType.SENIOR, BigDecimal.valueOf(0.7), // 30% 할인
		PassengerType.DISABLED_HEAVY, BigDecimal.valueOf(0.5), // 50% 할인 (보호자 1인 포함)
		PassengerType.DISABLED_LIGHT, BigDecimal.valueOf(0.7), // 30% 할인
		PassengerType.VETERAN, BigDecimal.valueOf(0.5) // 연 6회 무임, 6회 초과 시 50% 할인
	);

	/**
	 * 총 운임을 계산하는 메서드
	 * @param departureStationId 출발역 ID
	 * @param arrivalStationId 도착역 ID
	 * @param passengerTypes 승객 유형
	 * @param carType 객차 타입
	 * @return 할인이 적용 된 총 운임
	 */
	public BigDecimal calculateFare(
		Long departureStationId,
		Long arrivalStationId,
		List<PassengerType> passengerTypes,
		CarType carType
	) {
		// 요금 정보 조회
		StationFare stationFare = findStationFare(departureStationId, arrivalStationId);

		BigDecimal fare = getFareByCarType(stationFare, carType);

		return passengerTypes.stream().map(passengerType -> fare.multiply(DISCOUNT_RATES.get(passengerType)))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * 구간 요금 정보 조회
	 */
	private StationFare findStationFare(Long departureStationId, Long arrivalStationId) {
		return stationFareRepository
			.findByDepartureStationIdAndArrivalStationId(departureStationId, arrivalStationId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_FARE_NOT_FOUND));
	}

	/**
	 * 객차 타입에 해당하는 운임 선택
	 */
	private static BigDecimal getFareByCarType(StationFare stationFare, CarType carType) {
		return switch (carType) {
			case STANDARD -> BigDecimal.valueOf(stationFare.getStandardFare());
			case FIRST_CLASS -> BigDecimal.valueOf(stationFare.getFirstClassFare());
		};
	}
}
