package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.exception.TrainErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ServiceTest
@DisplayName("검증 실패, 요금 누락 관련 TrainSearchFacade 테스트")
public class TrainSearchFacadeValidationTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

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

		record ValidationScenario(
			String description,
			TrainSearchRequest request,
			Class<? extends Exception> expectedException,
			TrainErrorCode expectedErrorCode,
			String expectedMessageContains
		) {}

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

	@DisplayName("열차 검색 시 과거 시각을 출발 시간으로 선택하면 DEPARTURE_TIME_PASSED 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenDepartureTimeIsInPast() {
		// given
		int currentHour = LocalTime.now().getHour();
		assumeTrue(currentHour >= 1);

		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		TrainSearchRequest request = new TrainSearchRequest(
			seoul.getId(), busan.getId(), LocalDate.now(), 1,
			String.format("%02d", currentHour - 1)
		);

		// when & then
		assertThatThrownBy(() -> trainSearchFacade.searchTrains(request, PageRequest.of(0, 20)))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.DEPARTURE_TIME_PASSED.getMessage());
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
