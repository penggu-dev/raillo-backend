package com.sudo.raillo.train.application.facade;

import static com.sudo.raillo.support.helper.TrainScheduleTestHelper.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItem;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ServiceTest
class TrainSearchFacadeTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCarRepository trainCarRepository;

	@DisplayName("금일로부터 한달간의 운행 스케줄 캘린더를 조회한다.")
	@Test
	void getOperationCalendar() {
		// given
		Train train1 = trainTestHelper.createKTX();
		Train train2 = trainTestHelper.createCustomKTX(2, 1);

		LocalDate today = LocalDate.now();
		LocalDate tomorrow = today.plusDays(1);
		LocalDate dayAfterTomorrow = today.plusDays(2);
		LocalDate nextWeek = today.plusWeeks(1);

		createTrainSchedule(train1, today, "KTX 001", LocalTime.of(8, 0), LocalTime.of(11, 0));
		createTrainSchedule(train2, tomorrow, "KTX 003", LocalTime.of(14, 0), LocalTime.of(17, 0));
		createTrainSchedule(train1, nextWeek, "KTX 005", LocalTime.of(10, 0), LocalTime.of(13, 0));

		// when
		List<OperationCalendarItem> operationCalendar = trainSearchFacade.getOperationCalendar();

		// then
		// 1. 캘린더가 한 달치 날짜를 포함하는지 확인 (약 30일)
		assertThat(operationCalendar).hasSizeGreaterThanOrEqualTo(28).hasSizeLessThanOrEqualTo(32);

		// 2. 운행하는 날짜들이 isBookingAvailable = "Y"로 표시되는지 확인
		assertThat(operationCalendar).anyMatch(item ->
			item.operationDate().equals(today) && item.isBookingAvailable().equals("Y"));
		assertThat(operationCalendar).anyMatch(item ->
			item.operationDate().equals(tomorrow) && item.isBookingAvailable().equals("Y"));
		assertThat(operationCalendar).anyMatch(item ->
			item.operationDate().equals(nextWeek) && item.isBookingAvailable().equals("Y"));

		// 3. 운행하지 않는 날짜가 isBookingAvailable = "N"으로 표시되는지 확인
		assertThat(operationCalendar).anyMatch(item ->
			item.operationDate().equals(dayAfterTomorrow) && item.isBookingAvailable().equals("N"));

		// 4. 전체 캘린더에서 운행일과 비운행일이 모두 존재하는지 확인
		long operatingDays = operationCalendar.stream()
			.mapToLong(item -> item.isBookingAvailable().equals("Y") ? 1 : 0)
			.sum();
		long nonOperatingDays = operationCalendar.stream()
			.mapToLong(item -> item.isBookingAvailable().equals("N") ? 1 : 0)
			.sum();

		assertThat(operatingDays).isEqualTo(3); // today, tomorrow, nextWeek
		assertThat(nonOperatingDays).isGreaterThanOrEqualTo(0);
		assertThat(operatingDays + nonOperatingDays).isEqualTo(operationCalendar.size()); // 전체 합계 일치

		log.info("운행 캘린더 검증 완료: 전체 {} 일, 운행일 {} 일, 비운행일 {} 일",
			operationCalendar.size(), operatingDays, nonOperatingDays);
	}

	@DisplayName("검색 조건에 따른 열차를 조회한다.")
	@TestFactory
	List<DynamicTest> searchTrains() {
		// given
		Train train1 = trainTestHelper.createKTX();
		Train train2 = trainTestHelper.createCustomKTX(2, 1);
		Train train3 = trainTestHelper.createCustomKTX(3, 1);

		LocalDate futureDate = LocalDate.now().plusDays(1);

		// 오전, 오후, 저녁 시간대 열차 생성
		createTrainSchedule(train1, futureDate, "KTX 001", LocalTime.of(8, 0), LocalTime.of(11, 0));   // 오전
		createTrainSchedule(train2, futureDate, "KTX 003", LocalTime.of(14, 0), LocalTime.of(17, 0));  // 오후
		createTrainSchedule(train3, futureDate, "KTX 005", LocalTime.of(19, 0), LocalTime.of(22, 0));  // 저녁

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);

		// 검색 시나리오 정의
		record SearchScenario(
			String description,
			String departureHour,
			int expectedCount,
			java.util.function.Predicate<List<TrainSearchResponse>> validator
		) {
		}

		List<SearchScenario> scenarios = List.of(
			new SearchScenario(
				"전체 열차 조회 (06시 이후)",
				"06",
				3,
				trains -> trains.size() == 3 &&
					trains.stream().allMatch(train -> train.departureTime().isAfter(LocalTime.of(6, 0)))
			),
			new SearchScenario(
				"오후 이후 열차 조회 (13시 이후)",
				"13",
				2,
				trains -> trains.size() == 2 &&
					trains.stream().allMatch(train -> train.departureTime().isAfter(LocalTime.of(13, 0)))
			),
			new SearchScenario(
				"저녁 이후 열차 조회 (18시 이후)",
				"18",
				1,
				trains -> trains.size() == 1 &&
					trains.get(0).departureTime().isAfter(LocalTime.of(18, 0))
			),
			new SearchScenario(
				"심야 시간 조회 (23시 이후)",
				"23",
				0,
				trains -> trains.isEmpty()
			)
		);

		// DynamicTest 생성
		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description,
				() -> {
					// given
					TrainSearchRequest request = new TrainSearchRequest(
						seoul.getId(), busan.getId(), futureDate, 2, scenario.departureHour
					);
					Pageable pageable = PageRequest.of(0, 20);

					// when
					TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request,
						pageable);

					// then
					assertThat(response.content()).hasSize(scenario.expectedCount);
					assertThat(scenario.validator.test(response.content())).isTrue();

					// 페이징 정보 기본 검증
					assertThat(response.currentPage()).isEqualTo(0);
					assertThat(response.first()).isTrue();
					assertThat(response.numberOfElements()).isEqualTo(scenario.expectedCount);

					log.info("검색 시나리오 완료 - {}: {}시 이후 → {}건 조회",
						scenario.description, scenario.departureHour, response.content().size());
				}
			))
			.toList();
	}


	@DisplayName("열차 조회 시 지정한 출발 시간 이후에만 필터링하고, 결과를 시간순으로 정렬해서 반환한다")
	@TestFactory
	Collection<DynamicTest> shouldFilterByDepartureHourAndSortChronologically() {
		// given
		Train train1 = trainTestHelper.createRealisticTrain(1, 1, 8, 4); // 일반실 32석, 특실 12석
		Train train2 = trainTestHelper.createRealisticTrain(1, 1, 8, 4);
		Train train3 = trainTestHelper.createRealisticTrain(1, 1, 8, 4);

		LocalDate searchDate = LocalDate.now().plusDays(1);

		// 요금 정보 생성
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);

		// 다양한 시간대의 열차 생성 (시간순 정렬 테스트를 위해)
		createTrainSchedule(train1, searchDate, "KTX 001", LocalTime.of(6, 0), LocalTime.of(9, 15), "서울", "부산");
		createTrainSchedule(train2, searchDate, "KTX 003", LocalTime.of(12, 30), LocalTime.of(15, 45), "서울", "부산");
		createTrainSchedule(train3, searchDate, "KTX 005", LocalTime.of(18, 0), LocalTime.of(21, 15), "서울", "부산");

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		record TimeFilterScenario(
			String description,
			String departureHour,
			int expectedTrainCount,
			List<LocalTime> expectedDepartureTimes
		) {
		}

		List<TimeFilterScenario> scenarios = List.of(
			new TimeFilterScenario(
				"전체 열차 조회 (0시 이후)",
				"00",
				3,
				List.of(LocalTime.of(6, 0), LocalTime.of(12, 30), LocalTime.of(18, 0))
			),
			new TimeFilterScenario(
				"오전 중반 이후 열차 조회 (10시 이후)",
				"10",
				2,
				List.of(LocalTime.of(12, 30), LocalTime.of(18, 0))
			),
			new TimeFilterScenario(
				"오후 이후 열차 조회 (14시 이후)",
				"14",
				1,
				List.of(LocalTime.of(18, 0))
			),
			new TimeFilterScenario(
				"심야 시간 조회 (22시 이후)",
				"22",
				0,
				List.of()
			)
		);

		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description,
				() -> {
					// given
					TrainSearchRequest request = new TrainSearchRequest(
						seoul.getId(), busan.getId(), searchDate, 2, scenario.departureHour // 2명으로 고정 (수용력과 무관)
					);
					Pageable pageable = PageRequest.of(0, 20);

					// when
					TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, pageable);

					// then - 기본 검증
					assertThat(response.content()).hasSize(scenario.expectedTrainCount);
					assertThat(response.currentPage()).isEqualTo(0);
					assertThat(response.first()).isTrue();

					// 시간순 정렬 검증
					List<LocalTime> actualDepartureTimes = response.content().stream()
						.map(TrainSearchResponse::departureTime)
						.toList();

					assertThat(actualDepartureTimes)
						.as("출발 시간이 시간순으로 정렬되어야 합니다.")
						.isSorted()
						.containsExactlyElementsOf(scenario.expectedDepartureTimes);

					// 각 열차 기본 정보 검증
					response.content().forEach(train -> {
						assertThat(train.trainScheduleId()).isNotNull();
						assertThat(train.trainNumber()).isNotBlank();
						assertThat(train.trainName()).isEqualTo("KTX");
						assertThat(train.travelTime()).isPositive();
						assertThat(train.departureStationName()).isEqualTo("서울");
						assertThat(train.arrivalStationName()).isEqualTo("부산");
					});

					log.info("시간순 필터링 시나리오 완료 - {}: {}시 이후 → {}건 조회, 출발시간: {}",
						scenario.description, scenario.departureHour, response.content().size(),
						actualDepartureTimes.stream().map(Object::toString).collect(Collectors.joining(", ")));
				}
			))
			.toList();
	}


	@DisplayName("열차 조회 시 페이징이 올바르게 동작하고, 페이지 내/간 중복 없이 시간순 정렬을 유지한다")
	@Test
	void searchTrains_paginatesLargeResultsCorrectlyWithoutDuplicationAndMaintainsTimeOrdering() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);

		// 다양한 크기와 시간대로 10개 열차 생성
		List<Train> trains = IntStream.range(0, 10)
			.mapToObj(i -> {
				int standardCars = 2 + (i % 3); // 2-4개 일반실
				int firstClassCars = 1 + (i % 2); // 1-2개 특실
				return trainTestHelper.createRealisticTrain(standardCars, firstClassCars, 10, 6);
			})
			.toList();

		for (int i = 0; i < trains.size(); i++) {
			createTrainSchedule(trains.get(i), searchDate,
				String.format("KTX %03d", i + 1),
				LocalTime.of(6 + i, 0), // 6시부터 1시간 간격
				LocalTime.of(9 + i, 0),
				"서울", "부산");
		}

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		TrainSearchRequest request = new TrainSearchRequest(
			seoul.getId(), busan.getId(), searchDate, 1, "00"
		);

		// when
		record PagingTest(int pageSize, int expectedFirstPageSize, int expectedSecondPageSize) {
		}

		// 다양한 페이지 크기로 테스트
		List<PagingTest> pagingTests = List.of(
			new PagingTest(3, 3, 3), // 3개씩, 10개 → 3, 3, 3, 1
			new PagingTest(4, 4, 4), // 4개씩, 10개 → 4, 4, 2
			new PagingTest(7, 7, 3)  // 7개씩, 10개 → 7, 3
		);

		pagingTests.forEach(test -> {
			// 첫 번째 페이지
			Pageable firstPage = PageRequest.of(0, test.pageSize);
			TrainSearchSlicePageResponse firstResponse = trainSearchFacade.searchTrains(request, firstPage);

			// 두 번째 페이지
			Pageable secondPage = PageRequest.of(1, test.pageSize);
			TrainSearchSlicePageResponse secondResponse = trainSearchFacade.searchTrains(request, secondPage);

			// then - 첫 번째 페이지 검증
			assertThat(firstResponse.content()).hasSize(test.expectedFirstPageSize);
			assertThat(firstResponse.currentPage()).isEqualTo(0);
			assertThat(firstResponse.first()).isTrue();
			assertThat(firstResponse.hasNext()).isTrue();

			// then - 두 번째 페이지 검증
			assertThat(secondResponse.content()).hasSize(test.expectedSecondPageSize);
			assertThat(secondResponse.currentPage()).isEqualTo(1);
			assertThat(secondResponse.first()).isFalse();

			// 페이지 간 데이터 중복 없음 검증
			Set<Long> firstPageTrainScheduleIds = firstResponse.content().stream()
				.map(TrainSearchResponse::trainScheduleId)
				.collect(Collectors.toSet());
			Set<Long> secondPageTrainScheduleIds = secondResponse.content().stream()
				.map(TrainSearchResponse::trainScheduleId)
				.collect(Collectors.toSet());

			assertThat(firstPageTrainScheduleIds).doesNotContainAnyElementsOf(secondPageTrainScheduleIds);

			// 시간순 정렬 검증 (각 페이지 내에서)
			assertThat(firstResponse.content())
				.extracting(TrainSearchResponse::departureTime)
				.isSorted();
			assertThat(secondResponse.content())
				.extracting(TrainSearchResponse::departureTime)
				.isSorted();

			// 페이지 간 시간 순서 검증 (첫 페이지 마지막 < 둘째 페이지 첫번째)
			if (!firstResponse.content().isEmpty() && !secondResponse.content().isEmpty()) {
				LocalTime lastTimeFirstPage = firstResponse.content()
					.get(firstResponse.content().size() - 1)
					.departureTime();
				LocalTime firstTimeSecondPage = secondResponse.content().get(0).departureTime();
				assertThat(lastTimeFirstPage).isBefore(firstTimeSecondPage);
			}

			log.info("페이징 테스트 완료 (크기 {}): 1페이지 {}건, 2페이지 {}건",
				test.pageSize, firstResponse.content().size(), secondResponse.content().size());
		});
	}

	@DisplayName("조회하는 구간의 요금 정보가 없으면 STATION_FARE_NOT_FOUND 예외를 던진다")
	@Test
	void shouldThrowStationFareNotFoundWhenFareIsMissing() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);

		// 역 생성
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		// 서울→부산 요금 등록
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);

		Train train = trainTestHelper.createRealisticTrain(1, 1, 10, 6);
		// 요금 없는 방향으로 부산→서울 스케줄 생성
		createTrainSchedule(train, searchDate, "KTX Rev",
			LocalTime.of(15, 0), LocalTime.of(18, 0),
			"부산", "서울"
		);

		// when & then
		TrainSearchRequest request = new TrainSearchRequest(
			busan.getId(),    // 출발: 요금 미등록 방향
			seoul.getId(),    // 도착
			searchDate,
			1,
			"15"              // 15시 이후
		);

		assertThatThrownBy(() ->
			trainSearchFacade.searchTrains(request, PageRequest.of(0, 10))
		)
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> {
				BusinessException be = (BusinessException)ex;
				assertThat(be.getErrorCode())
					.isEqualTo(TrainErrorCode.STATION_FARE_NOT_FOUND);
				assertThat(be.getMessage())
					.contains(TrainErrorCode.STATION_FARE_NOT_FOUND.getMessage());
			});
	}


	@DisplayName("다양한 잘못된 검색 조건에 대해 적절한 비즈니스 예외가 발생한다")
	@TestFactory
	Collection<DynamicTest> shouldThrowAppropriateExceptionForInvalidSearchConditions() {
		// given
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		LocalDate validDate = LocalDate.now().plusDays(1);

		int currentHour = LocalTime.now().getHour();
		int pastHour = (currentHour == 0 ? 0 : currentHour - 1);
		String pastHourString = String.format("%02d", pastHour); // TODO : 시간 수정 필요

		record ValidationScenario(
			String description,
			TrainSearchRequest request,
			Class<? extends Exception> expectedException,
			TrainErrorCode expectedErrorCode,
			String expectedMessageContains
		) {
			@Override
			public String toString() {
				return description;
			}
		}

		List<ValidationScenario> scenarios = List.of(
			new ValidationScenario(
				"출발역과 도착역이 동일한 경우",
				new TrainSearchRequest(seoul.getId(), seoul.getId(), validDate, 1, "00"),
				BusinessException.class,
				TrainErrorCode.INVALID_ROUTE,
				TrainErrorCode.INVALID_ROUTE.getMessage()
			),
			new ValidationScenario(
				"운행일이 너무 먼 미래인 경우 (3개월 후)",
				new TrainSearchRequest(seoul.getId(), busan.getId(), LocalDate.now().plusMonths(3), 1, "00"),
				BusinessException.class,
				TrainErrorCode.OPERATION_DATE_TOO_FAR,
				TrainErrorCode.OPERATION_DATE_TOO_FAR.getMessage()
			),
			new ValidationScenario(
				"과거 시각을 출발 시간으로 선택한 경우",
				new TrainSearchRequest(seoul.getId(), busan.getId(), LocalDate.now(), 1, pastHourString),
				BusinessException.class,
				TrainErrorCode.DEPARTURE_TIME_PASSED,
				TrainErrorCode.DEPARTURE_TIME_PASSED.getMessage()
			)
		);

		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description,
				() -> {
					// when & then
					assertThatThrownBy(() -> trainSearchFacade.searchTrains(
						scenario.request, PageRequest.of(0, 20)))
						.isInstanceOf(scenario.expectedException)
						.hasMessageContaining(scenario.expectedMessageContains);

					log.info("검증 실패 시나리오 완료 - {}: {} 발생",
						scenario.description, scenario.expectedException.getSimpleName());
				}
			))
			.toList();
	}

	@DisplayName("다양한 검색 시나리오에서 검색 결과가 없을 경우 빈 리스트를 반환한다.")
	@TestFactory
	Collection<DynamicTest> shouldReturnEmptyListForNonexistentRoutesAndDates() {
		// given - 기본 테스트 데이터
		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 6);
		LocalDate searchDate = LocalDate.now().plusDays(1);

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);
		createTrainSchedule(train, searchDate, "KTX 001",
			LocalTime.of(10, 0), LocalTime.of(13, 0), "서울", "부산");

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		Station daegu = trainScheduleTestHelper.getOrCreateStation("대구");

		record NoResultScenario(
			String description,
			TrainSearchRequest request
		) {
			@Override
			public String toString() {
				return description;
			}
		}

		List<NoResultScenario> scenarios = List.of(
			new NoResultScenario(
				"존재하는 역이지만 해당 역을 경유하는 노선이 없는 경우 (서울-대구)",
				new TrainSearchRequest(seoul.getId(), daegu.getId(), searchDate, 1, "00")
			),
			new NoResultScenario(
				"해당 날짜에 운행하는 열차 없음",
				new TrainSearchRequest(seoul.getId(), busan.getId(), searchDate.plusDays(1), 1, "00")
			),
			new NoResultScenario(
				"요청한 출발 시간 이후에 운행하는 열차 없음",
				new TrainSearchRequest(seoul.getId(), busan.getId(), searchDate, 1, "15")
			)
		);

		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description,
				() -> {
					// when
					TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(
						scenario.request, PageRequest.of(0, 20));

					// then: 검색 결과 없을 때 빈 리스트 반환
					assertThat(response.content()).isEmpty();

					log.info("검색 결과 없음 시나리오 완료 - {}", scenario.description);
				}
			))
			.toList();
	}


	@DisplayName("잔여 좌석이 있는 객차 목록을 조회할 수 있다.")
	@Test
	void getAvailableTrainCars() {
		// given
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleWithStopStations scheduleWithStop = trainScheduleTestHelper.createSchedule(train);

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
		TrainScheduleWithStopStations scheduleWithStops = trainScheduleTestHelper.createSchedule(train);

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


	@DisplayName("좌석 상세 조회")
	@Test
	void getTrainCarSeatDetail_delegatesToSeatQueryService() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithStopStations scheduleWithStops = trainScheduleTestHelper.createSchedule(train);

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
		assertThat(searchResult.standardSeat().fare()).isEqualTo(50000); // 일반실 요금
		assertThat(searchResult.firstClassSeat().fare()).isEqualTo(100000); // 특실 요금
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

	private void createTrainSchedule(Train train, LocalDate operationDate, String scheduleName,
		LocalTime departureTime, LocalTime arrivalTime) {
		trainScheduleTestHelper.createCustomSchedule()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop("서울", null, departureTime)
			.addStop("부산", arrivalTime, null)
			.build();
	}

	/**
	 * 열차 스케줄 생성 헬퍼
	 */
	private TrainScheduleWithStopStations createTrainSchedule(Train train, LocalDate operationDate,
		String scheduleName, LocalTime departureTime, LocalTime arrivalTime,
		String departureStation, String arrivalStation) {
		return trainScheduleTestHelper.createCustomSchedule()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop(departureStation, null, departureTime)
			.addStop(arrivalStation, arrivalTime, null)
			.build();
	}
}
