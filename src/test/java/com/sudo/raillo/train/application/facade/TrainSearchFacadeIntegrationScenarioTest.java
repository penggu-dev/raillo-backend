package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
@DisplayName("열차 검색 전체 플로우 통합 테스트")
public class TrainSearchFacadeIntegrationScenarioTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;


	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCarRepository trainCarRepository;

	@DisplayName("전체 플로우 테스트 - 검색 -> 객차선택 -> 좌석조회 연동")
	@Test
	void fullSearchFlow_integrationTest() {
		// given
		Train train = trainTestHelper.createCustomKTX(2, 1);
		LocalDate futureDate = LocalDate.now().plusDays(1);
		createTrainSchedule(train, futureDate, "KTX 001", LocalTime.of(8, 0), LocalTime.of(11, 0));

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);

		// 1: 열차 검색
		TrainSearchRequest searchRequest = new TrainSearchRequest(
			seoul.getId(), busan.getId(), futureDate, 2, "00"
		);
		Pageable pageable = PageRequest.of(0, 20);

		TrainSearchSlicePageResponse searchResponse = trainSearchFacade.searchTrains(searchRequest,
			pageable);

		// 2: 검색된 열차의 객차 조회
		TrainCarListRequest carRequest = new TrainCarListRequest(
			searchResponse.content().get(0).trainScheduleId(),
			seoul.getId(), busan.getId(), 2
		);

		TrainCarListResponse carResponse = trainSearchFacade.getAvailableTrainCars(carRequest);

		// 3: 선택된 객차의 좌석 상세 조회
		String selectedCarNumber = carResponse.recommendedCarNumber();
		List<TrainCar> trainCars = trainCarRepository.findAllByTrainId(train.getId());
		TrainCar selectedCar = trainCars.stream()
			.filter(car -> String.format("%04d", car.getCarNumber()).equals(selectedCarNumber))
			.findFirst()
			.orElseThrow();

		TrainCarSeatDetailRequest seatRequest = new TrainCarSeatDetailRequest(
			selectedCar.getId(), searchResponse.content().get(0).trainScheduleId(),
			seoul.getId(), busan.getId()
		);

		TrainCarSeatDetailResponse seatResponse = trainSearchFacade.getTrainCarSeatDetail(seatRequest);

		// then
		// === Step 1: 열차 검색 결과 검증 ===
		assertThat(searchResponse.content()).hasSize(1);
		TrainSearchResponse searchResult = searchResponse.content().get(0);

		// 기본 열차 정보 검증
		assertThat(searchResult.trainScheduleId()).isNotNull();
		assertThat(searchResult.trainNumber()).isNotBlank();
		assertThat(searchResult.trainName()).isEqualTo("KTX");
		assertThat(searchResult.departureStationName()).isEqualTo("서울");
		assertThat(searchResult.arrivalStationName()).isEqualTo("부산");
		assertThat(searchResult.departureTime()).isEqualTo(LocalTime.of(8, 0));
		assertThat(searchResult.arrivalTime()).isEqualTo(LocalTime.of(11, 0));
		assertThat(searchResult.travelTime()).isEqualTo(Duration.ofHours(3));

		// 좌석 정보 검증
		assertThat(searchResult.standardSeat()).isNotNull();
		assertThat(searchResult.firstClassSeat()).isNotNull();
		assertThat(searchResult.standardSeat().fare()).isEqualByComparingTo(BigDecimal.valueOf(50000)); // 일반실 요금
		assertThat(searchResult.firstClassSeat().fare()).isEqualByComparingTo(BigDecimal.valueOf(100000)); // 특실 요금
		assertThat(searchResult.standardSeat().remainingSeats()).isGreaterThanOrEqualTo(0);
		assertThat(searchResult.firstClassSeat().remainingSeats()).isGreaterThanOrEqualTo(0);
		assertThat(searchResult.standardSeat().canReserve()).isNotNull();
		assertThat(searchResult.firstClassSeat().canReserve()).isNotNull();

		// === Step 2: 객차 조회 결과 검증 ===
		assertThat(carResponse.trainScheduleId()).isEqualTo(searchResult.trainScheduleId());
		assertThat(carResponse.trainClassificationCode()).isEqualTo("KTX");
		assertThat(carResponse.trainNumber()).isNotBlank();
		assertThat(carResponse.recommendedCarNumber()).isNotBlank();
		assertThat(carResponse.totalCarCount()).isEqualTo(2); // 일반실 1개 + 특실 1개
		assertThat(carResponse.carInfos()).hasSize(2);

		// 추천 객차가 실제 객차 목록에 존재하는지 검증
		List<String> availableCarNumbers = carResponse.carInfos().stream()
			.map(TrainCarInfo::carNumber)
			.toList();
		assertThat(availableCarNumbers).contains(carResponse.recommendedCarNumber());

		// 각 객차 정보 검증
		carResponse.carInfos().forEach(carInfo -> {
			assertThat(carInfo.carNumber()).isNotBlank();
			assertThat(carInfo.carType()).isIn(CarType.STANDARD, CarType.FIRST_CLASS);
			assertThat(carInfo.totalSeats()).isGreaterThan(0);
			assertThat(carInfo.remainingSeats()).isGreaterThanOrEqualTo(0);
			assertThat(carInfo.remainingSeats()).isLessThanOrEqualTo(carInfo.totalSeats());
		});

		// === Step 3: 좌석 상세 조회 결과 검증 ===
		assertThat(String.format("%04d", Integer.parseInt(seatResponse.carNumber()))).isEqualTo(selectedCarNumber);
		assertThat(seatResponse.carType()).isIn(CarType.STANDARD, CarType.FIRST_CLASS);
		assertThat(seatResponse.totalSeatCount()).isGreaterThan(0);
		assertThat(seatResponse.remainingSeatCount()).isGreaterThanOrEqualTo(0);
		assertThat(seatResponse.remainingSeatCount()).isLessThanOrEqualTo(seatResponse.totalSeatCount());
		assertThat(seatResponse.layoutType()).isIn(2, 3); // 2+2 또는 2+1 배치
		assertThat(seatResponse.seatList()).isNotEmpty();

		// 좌석 상세 정보 검증
		seatResponse.seatList().forEach(seat -> {
			assertThat(seat.seatId()).isNotNull();
			assertThat(seat.seatNumber()).isNotBlank();
			assertThat(seat.seatDirection()).isNotNull();
			assertThat(seat.seatType()).isNotNull();
			// available은 true/false 모두 가능
		});

		// 좌석 수 일관성 검증
		int totalSeatsInList = seatResponse.seatList().size();
		int availableSeatsInList = (int)seatResponse.seatList().stream()
			.mapToLong(seat -> seat.isAvailable() ? 1 : 0)
			.sum();

		assertThat(totalSeatsInList).isEqualTo(seatResponse.totalSeatCount());
		assertThat(availableSeatsInList).isEqualTo(seatResponse.remainingSeatCount());

		// === 플로우 간 데이터 일관성 검증 ===

		// 1. 검색 결과의 trainScheduleId가 모든 단계에서 일관되게 사용되는지
		assertThat(carRequest.trainScheduleId()).isEqualTo(searchResult.trainScheduleId());
		assertThat(seatRequest.trainScheduleId()).isEqualTo(searchResult.trainScheduleId());

		// 2. 선택된 객차가 실제 검색된 열차의 객차인지
		assertThat(selectedCar.getTrain().getId()).isEqualTo(train.getId());

		// 3. 객차 조회와 좌석 조회 간 데이터 일관성
		TrainCarInfo selectedCarInfo = carResponse.carInfos().stream()
			.filter(car -> car.carNumber().equals(selectedCarNumber) ||
				String.format("%04d", Integer.parseInt(car.carNumber())).equals(selectedCarNumber))
			.findFirst()
			.orElseThrow();

		assertThat(seatResponse.carType()).isEqualTo(selectedCarInfo.carType());
		assertThat(seatResponse.totalSeatCount()).isEqualTo(selectedCarInfo.totalSeats());
		assertThat(seatResponse.remainingSeatCount()).isEqualTo(selectedCarInfo.remainingSeats());

		// 4. 요청한 승객 수가 추천 객차에서 예약 가능한지 검증
		assertThat(selectedCarInfo.remainingSeats()).isGreaterThanOrEqualTo(2); // 요청한 승객 수

		// === 비즈니스 로직 검증 ===

		// 1. 추천 객차가 승객 수를 수용할 수 있는지
		assertThat(carResponse.carInfos().stream()
			.anyMatch(car -> car.carNumber().equals(carResponse.recommendedCarNumber()) &&
				car.remainingSeats() >= 2))
			.as("추천 객차는 요청한 승객 수(%d명)를 수용할 수 있어야 합니다", 2)
			.isTrue();

		// 2. 요금 정보가 올바르게 설정되었는지
		long standardSeatCars = carResponse.carInfos().stream()
			.mapToLong(car -> car.carType() == CarType.STANDARD ? 1 : 0)
			.sum();
		long firstClassCars = carResponse.carInfos().stream()
			.mapToLong(car -> car.carType() == CarType.FIRST_CLASS ? 1 : 0)
			.sum();

		assertThat(standardSeatCars).isEqualTo(1); // 일반실 1개
		assertThat(firstClassCars).isEqualTo(1);   // 특실 1개

		log.info("전체 플로우 테스트 완료: 검색{}건 → 객차{}개 → 좌석{}개, 추천객차={}, 잔여좌석={}",
			searchResponse.content().size(),
			carResponse.carInfos().size(),
			seatResponse.seatList().size(),
			carResponse.recommendedCarNumber(),
			seatResponse.remainingSeatCount());
	}

	/**
	 * 열차 스케줄 생성 헬퍼
	 */
	private void createTrainSchedule(Train train, LocalDate operationDate, String scheduleName,
		LocalTime departureTime, LocalTime arrivalTime) {
		trainScheduleTestHelper.builder()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop("서울", null, departureTime)
			.addStop("부산", arrivalTime, null)
			.build();
	}
}
