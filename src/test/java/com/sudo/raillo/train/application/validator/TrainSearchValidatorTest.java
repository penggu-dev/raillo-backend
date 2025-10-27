package com.sudo.raillo.train.application.validator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.StationRepository;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
class TrainSearchValidatorTest {

	@InjectMocks
	private TrainSearchValidator trainSearchValidator;

	@Mock
	private TrainScheduleRepository trainScheduleRepository;

	@Mock
	private TrainCarRepository trainCarRepository;

	@Mock
	private StationRepository stationRepository;

	// ===== 열차 스케줄 검색 요청 검증 테스트 =====

	@DisplayName("열차 스케줄 검색 시 다양한 유효하지 않은 조건에 대해 적절한 예외가 발생한다")
	@TestFactory
	Collection<DynamicTest> shouldThrowExceptionForInvalidScheduleSearchRequest() {
		given(stationRepository.existsById(anyLong())).willReturn(true);

		int currentHour = LocalTime.now().getHour();
		int pastHour = (currentHour == 0 ? 0 : currentHour - 1);
		String pastHourString = String.format("%02d", pastHour);

		record ValidationScenario(
			String description,
			TrainSearchRequest request,
			TrainErrorCode expectedErrorCode
		) {
			@Override
			public String toString() {
				return description;
			}
		}

		List<ValidationScenario> scenarios = List.of(
			new ValidationScenario(
				"출발역과 도착역이 동일한 경우",
				new TrainSearchRequest(1L, 1L, LocalDate.now().plusDays(1), 2, "10"),
				TrainErrorCode.INVALID_ROUTE
			),
			new ValidationScenario(
				"운행일이 1개월 이상 미래인 경우",
				new TrainSearchRequest(1L, 2L, LocalDate.now().plusMonths(2), 2, "10"),
				TrainErrorCode.OPERATION_DATE_TOO_FAR
			),
			new ValidationScenario(
				"오늘 날짜에 과거 시간을 선택한 경우",
				new TrainSearchRequest(1L, 2L, LocalDate.now(), 2, pastHourString),
				TrainErrorCode.DEPARTURE_TIME_PASSED
			)
		);

		return scenarios.stream()
			.map(scenario -> DynamicTest.dynamicTest(
				scenario.description,
				() -> {
					// when & then
					assertThatThrownBy(() -> trainSearchValidator.validateScheduleSearchRequest(scenario.request))
						.isInstanceOf(BusinessException.class)
						.hasMessageContaining(scenario.expectedErrorCode.getMessage());

					log.info("검증 실패 시나리오 완료 - {}: {} 예외 발생",
						scenario.description, scenario.expectedErrorCode);
				}
			))
			.toList();
	}

	@DisplayName("열차 스케줄 검색 시 유효한 요청은 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionForValidScheduleSearchRequest() {
		// given
		TrainSearchRequest validRequest = new TrainSearchRequest(
			1L, 2L, LocalDate.now().plusDays(1), 2, "10"
		);

		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateScheduleSearchRequest(validRequest))
			.doesNotThrowAnyException();

		log.info("유효한 스케줄 검색 요청 검증 성공");
	}

	@DisplayName("열차 스케줄 검색 시 오늘 날짜의 현재 시간 이후는 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionForCurrentOrFutureHourToday() {
		// given
		int currentHour = LocalTime.now().getHour();
		String currentHourString = String.format("%02d", currentHour);

		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, LocalDate.now(), 2, currentHourString
		);

		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateScheduleSearchRequest(request))
			.doesNotThrowAnyException();

		log.info("오늘 날짜의 현재 시간({}) 요청 검증 성공", currentHourString);
	}

	// ===== 객차 목록 조회 요청 검증 테스트 =====

	@DisplayName("객차 목록 조회 시 존재하지 않는 열차 스케줄이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenTrainScheduleNotExistsForCarList() {
		// given
		TrainCarListRequest request = new TrainCarListRequest(999L, 1L, 2L, 2);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarListRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND.getMessage());

		verify(trainScheduleRepository).existsById(999L);
		log.info("존재하지 않는 열차 스케줄 검증 예외 발생 완료");
	}

	@DisplayName("객차 목록 조회 시 존재하지 않는 출발역이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenDepartureStationNotExistsForCarList() {
		// given
		TrainCarListRequest request = new TrainCarListRequest(1L, 999L, 2L, 2);
		given(trainScheduleRepository.existsById(1L)).willReturn(true);
		given(stationRepository.existsById(999L)).willReturn(false);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarListRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.STATION_NOT_FOUND.getMessage());

		verify(stationRepository).existsById(999L);
		log.info("존재하지 않는 출발역 검증 예외 발생 완료");
	}

	@DisplayName("객차 목록 조회 시 존재하지 않는 도착역이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenArrivalStationNotExistsForCarList() {
		// given
		TrainCarListRequest request = new TrainCarListRequest(1L, 1L, 999L, 2);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(1L)).willReturn(true);
		given(stationRepository.existsById(999L)).willReturn(false);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarListRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.STATION_NOT_FOUND.getMessage());

		verify(stationRepository).existsById(999L);
		log.info("존재하지 않는 도착역 검증 예외 발생 완료");
	}

	@DisplayName("객차 목록 조회 시 출발역과 도착역이 동일하면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenDepartureAndArrivalStationSameForCarList() {
		// given
		TrainCarListRequest request = new TrainCarListRequest(1L, 1L, 1L, 2);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarListRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.INVALID_ROUTE.getMessage());

		log.info("출발역과 도착역이 동일한 경우 검증 예외 발생 완료");
	}

	@DisplayName("객차 목록 조회 시 유효한 요청은 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionForValidCarListRequest() {
		// given
		TrainCarListRequest request = new TrainCarListRequest(1L, 1L, 2L, 2);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateTrainCarListRequest(request))
			.doesNotThrowAnyException();

		verify(trainScheduleRepository).existsById(1L);
		verify(stationRepository).existsById(1L);
		verify(stationRepository).existsById(2L);
		log.info("유효한 객차 목록 조회 요청 검증 성공");
	}

	// ===== 좌석 상세 조회 요청 검증 테스트 =====

	@DisplayName("좌석 상세 조회 시 존재하지 않는 객차이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenTrainCarNotExistsForSeatDetail() {
		// given
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(999L, 1L, 1L, 2L);
		given(trainCarRepository.existsById(anyLong())).willReturn(false);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarSeatDetailRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.TRAIN_CAR_NOT_FOUND.getMessage());

		verify(trainCarRepository).existsById(999L);
		log.info("존재하지 않는 객차 검증 예외 발생 완료");
	}

	@DisplayName("좌석 상세 조회 시 존재하지 않는 열차 스케줄이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenTrainScheduleNotExistsForSeatDetail() {
		// given
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(1L, 999L, 1L, 2L);
		given(trainCarRepository.existsById(anyLong())).willReturn(true);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(false);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarSeatDetailRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND.getMessage());

		verify(trainScheduleRepository).existsById(999L);
		log.info("존재하지 않는 열차 스케줄 검증 예외 발생 완료");
	}

	@DisplayName("좌석 상세 조회 시 존재하지 않는 출발역이면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenDepartureStationNotExistsForSeatDetail() {
		// given
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(1L, 1L, 999L, 2L);
		given(trainCarRepository.existsById(anyLong())).willReturn(true);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(anyLong())).willReturn(false);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarSeatDetailRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.STATION_NOT_FOUND.getMessage());

		verify(stationRepository).existsById(999L);
		log.info("존재하지 않는 출발역 검증 예외 발생 완료");
	}

	@DisplayName("좌석 상세 조회 시 출발역과 도착역이 동일하면 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenDepartureAndArrivalStationSameForSeatDetail() {
		// given
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(1L, 1L, 1L, 1L);
		given(trainCarRepository.existsById(anyLong())).willReturn(true);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateTrainCarSeatDetailRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.INVALID_ROUTE.getMessage());

		log.info("출발역과 도착역이 동일한 경우 검증 예외 발생 완료");
	}

	@DisplayName("좌석 상세 조회 시 유효한 요청은 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionForValidSeatDetailRequest() {
		// given
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(1L, 1L, 1L, 2L);
		given(trainCarRepository.existsById(anyLong())).willReturn(true);
		given(trainScheduleRepository.existsById(anyLong())).willReturn(true);
		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateTrainCarSeatDetailRequest(request))
			.doesNotThrowAnyException();

		verify(trainCarRepository).existsById(1L);
		verify(trainScheduleRepository).existsById(1L);
		verify(stationRepository).existsById(1L);
		verify(stationRepository).existsById(2L);
		log.info("유효한 좌석 상세 조회 요청 검증 성공");
	}

	// ===== 경계값 테스트 =====

	@DisplayName("운행 날짜가 정확히 1개월 후인 경우 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionWhenOperationDateIsExactlyOneMonthLater() {
		// given
		LocalDate exactlyOneMonthLater = LocalDate.now().plusMonths(1);
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, exactlyOneMonthLater, 2, "10"
		);

		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateScheduleSearchRequest(request))
			.doesNotThrowAnyException();

		log.info("정확히 1개월 후 날짜 검증 성공: {}", exactlyOneMonthLater);
	}

	@DisplayName("운행 날짜가 1개월 + 1일 후인 경우 예외가 발생한다")
	@Test
	void shouldThrowExceptionWhenOperationDateIsOneMonthAndOneDayLater() {
		// given
		LocalDate oneMonthAndOneDayLater = LocalDate.now().plusMonths(1).plusDays(1);
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, oneMonthAndOneDayLater, 2, "10"
		);

		// when & then
		assertThatThrownBy(() -> trainSearchValidator.validateScheduleSearchRequest(request))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.OPERATION_DATE_TOO_FAR.getMessage());

		log.info("1개월 + 1일 후 날짜 검증 예외 발생: {}", oneMonthAndOneDayLater);
	}

	@DisplayName("오늘이 아닌 날짜는 과거 시간을 선택해도 예외가 발생하지 않는다")
	@Test
	void shouldNotThrowExceptionForPastHourOnFutureDate() {
		// given
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, LocalDate.now().plusDays(1), 2, "00"  // 내일 날짜의 00시
		);

		given(stationRepository.existsById(anyLong())).willReturn(true);

		// when & then
		assertThatCode(() -> trainSearchValidator.validateScheduleSearchRequest(request))
			.doesNotThrowAnyException();

		log.info("미래 날짜의 과거 시간 검증 성공 (예외 발생하지 않음)");
	}
}
