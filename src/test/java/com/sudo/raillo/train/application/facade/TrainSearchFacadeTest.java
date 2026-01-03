package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItem;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ServiceTest
@DisplayName("열차 검색 관련 TrainSearchFacade 테스트")
class TrainSearchFacadeTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

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

	/**
	 * 열차 스케줄 생성 헬퍼
	 */
	private void createTrainSchedule(Train train, LocalDate operationDate,
		String scheduleName, LocalTime departureTime, LocalTime arrivalTime,
		String departureStation, String arrivalStation) {
		trainScheduleTestHelper.builder()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop(departureStation, null, departureTime)
			.addStop(arrivalStation, arrivalTime, null)
			.build();
	}
}
