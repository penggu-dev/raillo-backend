package com.sudo.raillo.train.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class CarRecommendationServiceTest {

	private final CarRecommendationService service = new CarRecommendationService();

	@DisplayName("승객 수에 맞는 객차 추천 시 충분한 좌석이 있는 객차 중 중간 위치 객차를 선택한다")
	@Test
	void selectRecommendedCar_SelectsSuitableCar() {
		// given
		List<TrainCarInfo> cars = List.of(
			new TrainCarInfo(1L, "0001", CarType.STANDARD, 50, 2, "2+2"),
			new TrainCarInfo(2L, "0002", CarType.STANDARD, 50, 10, "2+2"),
			new TrainCarInfo(3L, "0003", CarType.STANDARD, 50, 15, "2+2")
		);
		int passengerCount = 5;

		// when
		String recommendedCar = service.selectRecommendedCar(cars, passengerCount);

		// then - 중간 객차 선택
		assertThat(recommendedCar).isEqualTo("0003");
		log.info("추천 객차 선정 완료: {} (승객 {}명)", recommendedCar, passengerCount);
	}

	@DisplayName("승객 수에 맞는 객차 추천 시 적합한 객차가 없으면 첫 번째 객차를 반환한다")
	@Test
	void selectRecommendedCar_FallbackToFirstCar() {
		// given
		List<TrainCarInfo> cars = List.of(
			new TrainCarInfo(1L, "0001", CarType.STANDARD, 50, 10, "2+2"),
			new TrainCarInfo(2L, "0002", CarType.STANDARD, 50, 15, "2+2")
		);
		int passengerCount = 20;  // 모든 객차가 부족

		// when
		String recommendedCar = service.selectRecommendedCar(cars, passengerCount);

		// then - Fallback으로 첫 번째 객차
		assertThat(recommendedCar).isEqualTo("0001");
		log.info("Fallback 객차 선정: {} (승객 {}명, 모든 객차 부족)", recommendedCar, passengerCount);
	}

	@DisplayName("승객 수에 맞는 객차 추천 시 사용 가능한 객차 목록이 비어있으면 NO_AVAILABLE_CARS 예외를 던진다")
	@Test
	void selectRecommendedCar_ThrowsWhenNoCarsAvailable() {
		// given
		List<TrainCarInfo> cars = List.of();
		int passengerCount = 5;

		// when & then
		assertThatThrownBy(() -> service.selectRecommendedCar(cars, passengerCount))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.NO_AVAILABLE_CARS.getMessage());

		log.info("객차 없음 예외 테스트 완료");
	}

	@DisplayName("승객 수에 맞는 객차 추천 시 사용 가능한 객차가 1개뿐이면 해당 객차를 반환한다")
	@Test
	void selectRecommendedCar_ReturnsSingleCar() {
		// given
		List<TrainCarInfo> cars = List.of(
			new TrainCarInfo(1L, "0001", CarType.STANDARD, 50, 30, "2+2")
		);
		int passengerCount = 10;

		// when
		String recommendedCar = service.selectRecommendedCar(cars, passengerCount);

		// then
		assertThat(recommendedCar).isEqualTo("0001");
		log.info("단일 객차 반환: {}", recommendedCar);
	}

	@DisplayName("승객 수에 맞는 객차 추천 시 홀수 개의 객차가 있으면 중간 위치의 객차를 선택한다")
	@Test
	void selectRecommendedCar_SelectsMiddleForOddCount() {
		// given
		List<TrainCarInfo> cars = List.of(
			new TrainCarInfo(1L, "0001", CarType.STANDARD, 50, 30, "2+2"),
			new TrainCarInfo(2L, "0002", CarType.STANDARD, 50, 35, "2+2"),
			new TrainCarInfo(3L, "0003", CarType.STANDARD, 50, 40, "2+2"),
			new TrainCarInfo(4L, "0004", CarType.STANDARD, 50, 32, "2+2"),
			new TrainCarInfo(5L, "0005", CarType.STANDARD, 50, 38, "2+2")
		);
		int passengerCount = 20;

		// when
		String recommendedCar = service.selectRecommendedCar(cars, passengerCount);

		// then - 5개 중 중간은 인덱스 2 (0003)
		assertThat(recommendedCar).isEqualTo("0003");
		log.info("홀수 개 객차 중 중간 선택: {}", recommendedCar);
	}
}
