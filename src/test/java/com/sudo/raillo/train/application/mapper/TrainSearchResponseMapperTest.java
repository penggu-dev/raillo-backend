package com.sudo.raillo.train.application.mapper;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.domain.StationFare;

@ServiceTest
class TrainSearchResponseMapperTest {

	@Autowired
	private TrainSearchResponseMapper mapper;

	private static final BigDecimal STANDARD_FARE = BigDecimal.valueOf(50000);
	private static final BigDecimal FIRST_CLASS_FARE = BigDecimal.valueOf(80000);

	@Test
	@DisplayName("일반실과 특실 모두 예약 가능한 경우 기본 응답을 올바르게 생성한다")
	void createBasicResponse() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(1L, 100, "KTX");
		SectionSeatStatus sectionStatus = new SectionSeatStatus(
			75, 80, 22, 24, true, true
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 4
		);

		// then
		assertThat(response.trainScheduleId()).isEqualTo(1L);
		assertThat(response.trainNumber()).isEqualTo("100");
		assertThat(response.trainName()).isEqualTo("KTX");
		assertThat(response.standardSeat().remainingSeats()).isEqualTo(75);
		assertThat(response.standardSeat().canReserve()).isTrue();
		assertThat(response.firstClassSeat().remainingSeats()).isEqualTo(22);
		assertThat(response.firstClassSeat().canReserve()).isTrue();
	}

	@Test
	@DisplayName("특실이 부족한 경우 일반실만 예약 가능으로 표시한다")
	void onlyStandardReservable() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(2L, 200, "KTX");
		SectionSeatStatus sectionStatus = new SectionSeatStatus(
			50, 80, 2, 24, true, false
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 4
		);

		// then
		assertThat(response.standardSeat().canReserve()).isTrue();
		assertThat(response.firstClassSeat().canReserve()).isFalse();
	}

	@Test
	@DisplayName("일반실이 모두 부족한 경우 특실만 예약 가능으로 표시한다")
	void onlyFirstClassReservable() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(3L, 300, "KTX");
		SectionSeatStatus sectionStatus = new SectionSeatStatus(
			2, 80,      // 일반실: 잔여 2석 / 총 80석
			20, 24,     // 특실: 잔여 20석 / 총 24석
			false,      // 일반실 예약 불가
			true       // 특실 예약 가능
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 5
		);

		// then
		assertThat(response.standardSeat().canReserve()).isFalse();
		assertThat(response.firstClassSeat().canReserve()).isTrue();
	}

	private TrainBasicInfo createTrainBasicInfo(Long scheduleId, int trainNumber, String trainName) {
		return new TrainBasicInfo(
			scheduleId, trainNumber, trainName,
			"서울", "부산",
			LocalTime.of(10, 0), LocalTime.of(13, 0), 0, 2);
	}

	private StationFare createStationFare() {
		return new StationFare(
			null,  // id
			null,  // departureStation
			null,  // arrivalStation
			STANDARD_FARE,
			FIRST_CLASS_FARE
		);
	}
}
