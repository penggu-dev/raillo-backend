package com.sudo.raillo.train.application.service;

import static com.sudo.raillo.support.helper.TrainScheduleTestHelper.*;
import static com.sudo.raillo.train.exception.TrainErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.fixture.StationFareFixture;
import com.sudo.raillo.support.fixture.StationFixture;
import com.sudo.raillo.support.fixture.TrainFixture;
import com.sudo.raillo.support.fixture.TrainScheduleFixture;
import com.sudo.raillo.train.application.dto.SeatReservationInfo;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.TrainScheduleBasicInfo;
import com.sudo.raillo.train.application.dto.projection.TrainSeatInfoBatch;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.TrainType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.SeatReservationRepositoryCustom;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepositoryCustom;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
class TrainSearchServiceTest {

	@InjectMocks
	private TrainSearchService trainSearchService;

	@Mock
	private TrainScheduleRepository trainScheduleRepository;

	@Mock
	private TrainScheduleRepositoryCustom trainScheduleRepositoryCustom;

	@Mock
	private StationFareRepository stationFareRepository;

	@Mock
	private SeatReservationRepositoryCustom seatReservationRepositoryCustom;

	@DisplayName("출발역, 도착역, 운행일, 출발시간으로 열차 기본 정보를 조회한다")
	@Test
	void findsTrainBasicInfoBySearchConditions() {
		// given
		Long departureStationId = 1L;
		Long arrivalStationId = 2L;
		LocalDate operationDate = LocalDate.now().plusDays(1);
		String departureHour = "10";

		TrainSearchRequest request = new TrainSearchRequest(
			departureStationId, arrivalStationId, operationDate, 2, departureHour
		);
		Pageable pageable = PageRequest.of(0, 20);

		List<TrainBasicInfo> trainInfoList = List.of(
			new TrainBasicInfo(
				1L,                    // trainScheduleId
				1,                     // trainNumber
				"KTX",                 // trainName
				"서울",                 // departureStationName
				"부산",                 // arrivalStationName
				LocalTime.of(10, 0),   // departureTime
				LocalTime.of(13, 0)    // arrivalTime
			)
		);
		Slice<TrainBasicInfo> trainSlice = new SliceImpl<>(trainInfoList, pageable, false);

		given(trainScheduleRepositoryCustom.findTrainBasicInfo(
			departureStationId, arrivalStationId, operationDate, LocalTime.of(10, 0), pageable
		)).willReturn(trainSlice);

		// when
		Slice<TrainBasicInfo> result = trainSearchService.findTrainBasicInfo(request, pageable);

		// then
		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).trainScheduleId()).isEqualTo(1L);
		assertThat(result.getContent().get(0).trainName()).isEqualTo("KTX");

		verify(trainScheduleRepositoryCustom).findTrainBasicInfo(
			departureStationId, arrivalStationId, operationDate, LocalTime.of(10, 0), pageable
		);
	}

	// ===== 요금 정보 조회 =====

	@DisplayName("출발역과 도착역으로 구간 요금을 조회한다")
	@Test
	void findsStationFareBetweenStations() {
		// given
		Long departureStationId = 1L;
		Long arrivalStationId = 2L;

		Station departureStation = StationFixture.create("서울");
		Station arrivalStation = StationFixture.create("부산");
		StationFare fare = StationFareFixture.create(departureStation, arrivalStation, 50000, 80000);

		given(stationFareRepository.findByDepartureStationIdAndArrivalStationId(
			departureStationId, arrivalStationId
		)).willReturn(Optional.of(fare));

		// when
		StationFare result = trainSearchService.findStationFare(departureStationId, arrivalStationId);

		// then
		assertThat(result).isNotNull();
		assertThat(result.getStandardFare()).isEqualTo(50000);
		assertThat(result.getFirstClassFare()).isEqualTo(80000);

		verify(stationFareRepository).findByDepartureStationIdAndArrivalStationId(
			departureStationId, arrivalStationId
		);
	}

	@DisplayName("구간 요금이 존재하지 않으면 예외가 발생한다")
	@Test
	void throwsExceptionWhenStationFareNotFound() {
		// given
		Long departureStationId = 1L;
		Long arrivalStationId = 2L;

		given(stationFareRepository.findByDepartureStationIdAndArrivalStationId(
			departureStationId, arrivalStationId
		)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> trainSearchService.findStationFare(departureStationId, arrivalStationId))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.STATION_FARE_NOT_FOUND.getMessage());

		verify(stationFareRepository).findByDepartureStationIdAndArrivalStationId(
			departureStationId, arrivalStationId
		);
	}

	// ===== 열차 스케줄 기본 정보 조회 =====

	@DisplayName("스케줄 ID로 열차 스케줄 기본 정보를 조회한다")
	@Test
	void getsTrainScheduleBasicInfoById() throws NoSuchFieldException, IllegalAccessException {
		// given
		Long trainScheduleId = 1L;

		Train train = TrainFixture.create(1, TrainType.KTX, "KTX", 8);
		Station seoul = StationFixture.create("서울");
		Station busan = StationFixture.create("부산");

		TrainSchedule trainSchedule = TrainScheduleFixture.create(
			"KTX 001",
			LocalDate.now().plusDays(1),
			LocalTime.of(10, 0),
			LocalTime.of(13, 0),
			OperationStatus.ACTIVE,
			train,
			seoul,
			busan
		);

		Field idField = TrainSchedule.class.getDeclaredField("id");
		idField.setAccessible(true);
		idField.set(trainSchedule, trainScheduleId);

		given(trainScheduleRepository.findById(trainScheduleId))
			.willReturn(Optional.of(trainSchedule));

		// when
		TrainScheduleBasicInfo result = trainSearchService.getTrainScheduleBasicInfo(trainScheduleId);

		// then
		assertThat(result).isNotNull();
		assertThat(result.scheduleId()).isEqualTo(trainScheduleId);
		assertThat(result.trainClassificationCode()).isEqualTo("KTX");

		verify(trainScheduleRepository).findById(trainScheduleId);
	}

	@DisplayName("존재하지 않는 스케줄 ID로 조회하면 예외가 발생한다")
	@Test
	void getTrainScheduleBasicInfo_ThrowsWhenNotFound() {
		// given
		Long trainScheduleId = 999L;

		given(trainScheduleRepository.findById(trainScheduleId))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> trainSearchService.getTrainScheduleBasicInfo(trainScheduleId))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TrainErrorCode.TRAIN_SCHEDULE_DETAIL_NOT_FOUND.getMessage());

		verify(trainScheduleRepository).findById(trainScheduleId);
	}

	// ===== 배치 조회 메서드 =====

	@DisplayName("여러 열차의 좌석 정보를 한 번에 조회한다")
	@Test
	void findsTrainSeatInfoInBatch() {
		// given
		List<Long> trainScheduleIds = List.of(1L, 2L, 3L);

		Map<Long, Map<CarType, Integer>> mockSeatsCountByCarType = Map.of(
			1L, Map.of(CarType.STANDARD, 120, CarType.FIRST_CLASS, 36),
			2L, Map.of(CarType.STANDARD, 100, CarType.FIRST_CLASS, 30),
			3L, Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24)
		);

		Map<Long, Integer> mockTotalSeatsCount = Map.of(
			1L, 156,  // 120 + 36
			2L, 130,  // 100 + 30
			3L, 104   // 80 + 24
		);

		TrainSeatInfoBatch batchInfo = new TrainSeatInfoBatch(
			mockSeatsCountByCarType,
			mockTotalSeatsCount
		);

		given(trainScheduleRepositoryCustom.findTrainSeatInfoBatch(trainScheduleIds))
			.willReturn(batchInfo);

		// when
		TrainSeatInfoBatch result = trainSearchService.findTrainSeatInfoBatch(trainScheduleIds);

		// then
		assertThat(result).isNotNull();
		assertThat(result.getSeatsCountByCarType(1L))
			.containsEntry(CarType.STANDARD, 120)
			.containsEntry(CarType.FIRST_CLASS, 36);

		verify(trainScheduleRepositoryCustom).findTrainSeatInfoBatch(trainScheduleIds);
	}

	@DisplayName("여러 열차의 겹치는 예약 정보를 한 번에 조회한다")
	@Test
	void findsOverlappingReservationsInBatch() {
		// given
		List<Long> trainScheduleIds = List.of(1L, 2L);
		Long departureStationId = 1L;
		Long arrivalStationId = 2L;

		Map<Long, List<SeatReservationInfo>> mockReservations = Map.of(
			1L, List.of(new SeatReservationInfo(1L, CarType.STANDARD, 1L, 2L)),
			2L, List.of(new SeatReservationInfo(2L, CarType.FIRST_CLASS, 1L, 2L))
		);

		given(seatReservationRepositoryCustom.findOverlappingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		)).willReturn(mockReservations);

		// when
		Map<Long, List<SeatReservationInfo>> result = trainSearchService.findOverlappingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		);

		// then
		assertThat(result).hasSize(2);
		assertThat(result.get(1L)).hasSize(1);

		verify(seatReservationRepositoryCustom).findOverlappingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		);
		log.info("겹치는 예약 배치 조회 테스트 완료");
	}

	@DisplayName("여러 열차의 입석 예약 수를 한 번에 조회한다")
	@Test
	void countsStandingReservationsInBatch() {
		// given
		List<Long> trainScheduleIds = List.of(1L, 2L);
		Long departureStationId = 1L;
		Long arrivalStationId = 2L;

		Map<Long, Integer> mockStandingCounts = Map.of(
			1L, 5,
			2L, 3
		);

		given(seatReservationRepositoryCustom.countOverlappingStandingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		)).willReturn(mockStandingCounts);

		// when
		Map<Long, Integer> result = trainSearchService.countOverlappingStandingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		);

		// then
		assertThat(result).hasSize(2);
		assertThat(result.get(1L)).isEqualTo(5);
		assertThat(result.get(2L)).isEqualTo(3);

		verify(seatReservationRepositoryCustom).countOverlappingStandingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId
		);
	}
}
