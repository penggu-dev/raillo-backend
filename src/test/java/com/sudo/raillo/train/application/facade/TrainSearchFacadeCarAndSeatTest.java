package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.ScheduleWithStops;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
@DisplayName("객차/좌석 조회 관련 TrainSearchFacade 테스트")
public class TrainSearchFacadeCarAndSeatTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCarRepository trainCarRepository;

	@DisplayName("잔여 좌석이 있는 객차 목록을 조회할 수 있다.")
	@Test
	void getAvailableTrainCars() {
		// given
		Train train = trainTestHelper.createCustomKTX(3, 2);
		ScheduleWithStops scheduleWithStop = trainScheduleTestHelper.createSchedule(train);

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		TrainCarListRequest request = new TrainCarListRequest(
			scheduleWithStop.trainSchedule().getId(),
			seoul.getId(),
			busan.getId(),
			2
		);

		// when
		TrainCarListResponse response = trainSearchFacade.getAvailableTrainCars(request);

		// then
		assertThat(response.trainClassificationCode()).isEqualTo("KTX");
		assertThat(response.trainNumber()).isNotBlank();
		assertThat(response.totalCarCount()).isGreaterThan(0);
		assertThat(response.totalCarCount()).isEqualTo(response.carInfos().size());

		List<String> carNumbers = response.carInfos().stream()
			.map(car -> car.carNumber())
			.toList();
		assertThat(carNumbers).contains(response.recommendedCarNumber());

		log.info("객차 목록 조회 완료: 열차 = {}-{}, 추천객차 = {}",
			response.trainClassificationCode(), response.trainNumber(), response.recommendedCarNumber());
	}
	// TODO : 객차 타입별 객차수 다채롭게 두어 테스트 필요

	@DisplayName("승객 수에 따라 추천에 적합한 객차(잔여 좌석수 > 승객 수)가 있다면 중간 객차를, 없으면 첫 번째 객차를 추천한다.")
	@TestFactory
	Collection<DynamicTest> getAvailableTrainCars_recommendationLogicScenarios() {
		// given
		Train train = trainTestHelper.createCustomKTX(6, 2);
		ScheduleWithStops scheduleWithStops = trainScheduleTestHelper.createSchedule(train);

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		record RecommendationScenario(
			String description,
			int passengerCount,
			String expectedBehavior,
			Predicate<TrainCarListResponse> validator
		) {
		}

		List<RecommendationScenario> scenarios = List.of(
			new RecommendationScenario(
				"일반적인 승객 수 - 수용 가능한 객차 추천",
				2,
				"승객 수를 수용할 수 있는 객차 중에서 선택",
				response -> {
					// 추천 객차가 승객 수를 수용할 수 있는지
					TrainCarInfo recommendedCar = response.carInfos().stream()
						.filter(car -> car.carNumber().equals(response.recommendedCarNumber()))
						.findFirst()
						.orElse(null);

					return recommendedCar != null && recommendedCar.remainingSeats() >= 2;
				}
			),
			new RecommendationScenario(
				"수용 불가능한 승객 수 - fallback 로직",
				20,
				"모든 객차가 수용 불가능할 때 첫 번째 객차 또는 가장 큰 객차 선택",
				response -> {
					TrainCarInfo recommendedCar = response.carInfos().stream()
						.filter(car -> car.carNumber().equals(response.recommendedCarNumber()))
						.findFirst()
						.orElse(null);

					if (recommendedCar == null)
						return false;

					// 모든 객차가 20명을 수용할 수 없으므로 fallback 로직 적용
					boolean isFirstCar = response.carInfos().get(0).carNumber().equals(response.recommendedCarNumber());
					boolean isLargestCar = response.carInfos().stream()
						.allMatch(car -> car.remainingSeats() <= recommendedCar.remainingSeats());

					return isFirstCar || isLargestCar;
				}
			)
		);

		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description + " (승객 " + scenario.passengerCount + "명)",
				() -> {
					// given
					TrainCarListRequest request = new TrainCarListRequest(
						scheduleWithStops.trainSchedule().getId(),
						seoul.getId(),
						busan.getId(),
						scenario.passengerCount
					);

					// when
					TrainCarListResponse response = trainSearchFacade.getAvailableTrainCars(request);

					// then
					assertThat(response.carInfos()).isNotEmpty();
					assertThat(response.recommendedCarNumber()).isNotBlank();
					assertThat(response.totalCarCount()).isEqualTo(2); // 일반실 1개 + 특실 1개

					// 추천 객차가 실제 객차 목록에 포함되어 있는지
					List<String> availableCarNumbers = response.carInfos().stream()
						.map(TrainCarInfo::carNumber)
						.toList();
					assertThat(availableCarNumbers).contains(response.recommendedCarNumber());

					// 각 객차 정보 상세 검증
					response.carInfos().forEach(carInfo -> {
						assertThat(carInfo.carNumber()).isNotBlank();
						assertThat(carInfo.carType()).isIn(CarType.STANDARD, CarType.FIRST_CLASS);
						assertThat(carInfo.totalSeats()).isGreaterThan(0);
						assertThat(carInfo.remainingSeats()).isGreaterThanOrEqualTo(0);
						assertThat(carInfo.remainingSeats()).isLessThanOrEqualTo(carInfo.totalSeats());
					});

					// 객차 타입별 분포 검증
					// TODO : 객차 타입별 객차수 다채롭게 두어 테스트 필요
					long standardCars = response.carInfos().stream()
						.mapToLong(car -> car.carType() == CarType.STANDARD ? 1 : 0)
						.sum();
					long firstClassCars = response.carInfos().stream()
						.mapToLong(car -> car.carType() == CarType.FIRST_CLASS ? 1 : 0)
						.sum();

					assertThat(standardCars + firstClassCars).isEqualTo(response.totalCarCount());
					assertThat(standardCars + firstClassCars).isEqualTo(response.carInfos().size());

					// 시나리오별 비즈니스 로직 검증
					assertThat(scenario.validator.test(response))
						.as("시나리오 '%s'의 추천 로직이 올바르게 작동해야 합니다. 추천객차: %s, 기대동작: %s",
							scenario.description, response.recommendedCarNumber(), scenario.expectedBehavior)
						.isTrue();

					TrainCarInfo recommendedCar = response.carInfos().stream()
						.filter(car -> car.carNumber().equals(response.recommendedCarNumber()))
						.findFirst()
						.orElseThrow();

					log.info("추천 로직 검증 완료 - {}: 승객{}명 → 객차{} (잔여{}석), 총 {}개 객차 중",
						scenario.description, scenario.passengerCount,
						response.recommendedCarNumber(), recommendedCar.remainingSeats(),
						response.carInfos().size());
				}
			))
			.toList();
	}

	@DisplayName("객차의 좌석 상세 정보를 조회한다")
	@Test
	void getTrainCarSeatDetail_delegatesToSeatQueryService() {
		// given
		Train train = trainTestHelper.createKTX();
		ScheduleWithStops scheduleWithStops = trainScheduleTestHelper.createSchedule(train);

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		List<TrainCar> trainCars = trainCarRepository.findAllByTrainId(train.getId());
		TrainCar firstCar = trainCars.get(0);

		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(
			firstCar.getId(), scheduleWithStops.trainSchedule().getId(),
			seoul.getId(), busan.getId()
		);

		// when
		TrainCarSeatDetailResponse response = trainSearchFacade.getTrainCarSeatDetail(request);

		// then
		assertThat(response.carNumber()).isEqualTo(Integer.valueOf(firstCar.getCarNumber()).toString());
		assertThat(response.carType()).isEqualTo(firstCar.getCarType());
		assertThat(response.totalSeatCount()).isEqualTo(firstCar.getTotalSeats());
		assertThat(response.remainingSeatCount()).isGreaterThanOrEqualTo(0)
			.isLessThanOrEqualTo(firstCar.getTotalSeats());

		log.info("좌석 상세 조회 완료: 객차={}, 좌석타입={}",
			response.carNumber(), response.carType());
	}
}
