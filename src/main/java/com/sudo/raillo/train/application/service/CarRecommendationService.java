package com.sudo.raillo.train.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.exception.TrainErrorCode;

@Service
public class CarRecommendationService {

	/**
	 * 승객 수에 맞는 추천 객차 선정
	 *
	 * TODO: 조금 더 고도화된 객차 추천 알고리즘 필요
	 * 1. 승객 수 이상의 좌석을 가진 객차 필터링
	 * 2. 여러 객차가 있으면 중간 위치 선택 (승객 분산)
	 * 3. 적합한 객차가 없으면 첫 번째 객차 반환
	 */
	public String selectRecommendedCar(List<TrainCarInfo> availableCars, int passengerCount) {
		if (availableCars.isEmpty()) {
			throw new BusinessException(TrainErrorCode.NO_AVAILABLE_CARS);
		}

		List<TrainCarInfo> suitableCars = findSuitableCars(availableCars, passengerCount);

		if (suitableCars.isEmpty()) {
			return availableCars.get(0).carNumber(); // Fallback
		}

		return selectMiddleCar(suitableCars);
	}

	/**
	 * 승객 수를 수용할 수 있는 객차 필터링
	 */
	private List<TrainCarInfo> findSuitableCars(List<TrainCarInfo> cars, int passengerCount) {
		return cars.stream()
			.filter(car -> car.remainingSeats() >= passengerCount)
			.toList();
	}

	/**
	 * 중간 위치 객차 선택
	 */
	private String selectMiddleCar(List<TrainCarInfo> cars) {
		int middleIndex = cars.size() / 2;
		return cars.get(middleIndex).carNumber();
	}
}
