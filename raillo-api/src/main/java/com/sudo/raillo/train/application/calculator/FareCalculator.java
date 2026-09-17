package com.sudo.raillo.train.application.calculator;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FareCalculator {

	private final StationFareRepository stationFareRepository;

	private static final Map<PassengerType, BigDecimal> DISCOUNT_RATES = Map.of(
		PassengerType.ADULT, BigDecimal.valueOf(1.0), // 정상가
		PassengerType.CHILD, BigDecimal.valueOf(0.6), // 40% 할인
		PassengerType.INFANT, BigDecimal.valueOf(0.25), // 75% 할인
		PassengerType.SENIOR, BigDecimal.valueOf(0.7), // 30% 할인
		PassengerType.DISABLED_HEAVY, BigDecimal.valueOf(0.5), // 50% 할인
		PassengerType.DISABLED_LIGHT, BigDecimal.valueOf(0.7), // 30% 할인
		PassengerType.VETERAN, BigDecimal.valueOf(0.5) // 50% 할인
	);

	/**
	 * 개별 좌석 운임을 계산하는 메서드
	 * @param departureStationId 출발역 ID
	 * @param arrivalStationId 도착역 ID
	 * @param passengerType 승객 유형
	 * @param carType 객차 타입
	 * @return 할인이 적용된 개별 운임
	 */
	public BigDecimal calculateFare(
		Long departureStationId,
		Long arrivalStationId,
		PassengerType passengerType,
		CarType carType
	) {
		StationFare stationFare = findStationFare(departureStationId, arrivalStationId);
		BigDecimal baseFare = getFareByCarType(stationFare, carType);
		return baseFare.multiply(DISCOUNT_RATES.get(passengerType));
	}

	/**
	 * 기준정보 캐시의 구간 운임으로 좌석별 운임을 계산한다. 결과는 승객 유형 순서와 같다.
	 */
	public List<BigDecimal> calculateFares(
		StationFareCacheValue stationFare,
		CarType carType,
		List<PassengerType> passengerTypes
	) {
		BigDecimal baseFare = getFareByCarType(stationFare, carType);
		return passengerTypes.stream()
			.map(passengerType -> applyDiscount(baseFare, passengerType))
			.toList();
	}

	public BigDecimal calculateFare(StationFareCacheValue stationFare, CarType carType, PassengerType passengerType) {
		return applyDiscount(getFareByCarType(stationFare, carType), passengerType);
	}

	private BigDecimal applyDiscount(BigDecimal baseFare, PassengerType passengerType) {
		return baseFare.multiply(DISCOUNT_RATES.get(passengerType));
	}

	private BigDecimal getFareByCarType(StationFareCacheValue stationFare, CarType carType) {
		return carType == CarType.FIRST_CLASS ? stationFare.firstClassFare() : stationFare.standardFare();
	}

	/**
	 * 구간 요금 정보 조회
	 */
	private StationFare findStationFare(Long departureStationId, Long arrivalStationId) {
		return stationFareRepository
			.findByDepartureStationIdAndArrivalStationId(departureStationId, arrivalStationId)
			.orElseThrow(() -> new BusinessException(TrainError.STATION_FARE_NOT_FOUND));
	}

	/**
	 * 객차 타입에 해당하는 운임 선택
	 */
	private static BigDecimal getFareByCarType(StationFare stationFare, CarType carType) {
		return switch (carType) {
			case STANDARD -> stationFare.getStandardFare();
			case FIRST_CLASS -> stationFare.getFirstClassFare();
		};
	}
}
