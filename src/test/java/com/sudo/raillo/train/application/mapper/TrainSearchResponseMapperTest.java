package com.sudo.raillo.train.application.mapper;

import static org.assertj.core.api.Assertions.*;

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

	private static final double STANDING_RATIO = 0.15;
	private static final int STANDARD_FARE = 50000;
	private static final int FIRST_CLASS_FARE = 80000;

	@Test
	@DisplayName("일반실과 특실 모두 예약 가능한 경우 기본 응답을 올바르게 생성한다")
	void createBasicResponse() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(1L, 100, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			75, 80, 22, 24, true, true, 0, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 4, STANDING_RATIO
		);

		// then
		assertThat(response.trainScheduleId()).isEqualTo(1L);
		assertThat(response.trainNumber()).isEqualTo("100");
		assertThat(response.trainName()).isEqualTo("KTX");
		assertThat(response.standardSeat().remainingSeats()).isEqualTo(75);
		assertThat(response.standardSeat().canReserve()).isTrue();
		assertThat(response.firstClassSeat().remainingSeats()).isEqualTo(22);
		assertThat(response.firstClassSeat().canReserve()).isTrue();
		assertThat(response.standing()).isNull();
	}

	@Test
	@DisplayName("특실이 부족한 경우 일반실만 예약 가능으로 표시한다")
	void onlyStandardReservable() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(2L, 200, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			50, 80, 2, 24, true, false, 0, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 4, STANDING_RATIO
		);

		// then
		assertThat(response.standardSeat().canReserve()).isTrue();
		assertThat(response.firstClassSeat().canReserve()).isFalse();
		assertThat(response.standing()).isNull();
	}

	@Test
	@DisplayName("일반실과 입석 모두 부족한 경우 입석 정보를 포함하지 않고 특실만 예약 가능으로 표시한다")
	void onlyFirstClassReservable() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(3L, 300, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			2, 80,      // 일반실: 잔여 2석 / 총 80석
			20, 24,     // 특실: 잔여 20석 / 총 24석
			false,      // 일반실 예약 불가
			true,       // 특실 예약 가능
			13,         // 입석 예약: 13석 (15 - 13 = 2석만 남음 < 5명)
			104         // 총 좌석
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 5, STANDING_RATIO
		);

		// then
		assertThat(response.standardSeat().canReserve()).isFalse();
		assertThat(response.firstClassSeat().canReserve()).isTrue();
		assertThat(response.hasStandingInfo()).isFalse();
		assertThat(response.standing()).isNull();
	}

	@Test
	@DisplayName("일반실이 매진되고 입석이 가능한 경우 입석 정보를 포함한다")
	void includeStandingInfo() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(4L, 400, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			0, 80, 24, 24, false, true, 0, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 3, STANDING_RATIO
		);

		// then
		assertThat(response.hasStandingInfo()).isTrue();
		assertThat(response.standing()).isNotNull();
		assertThat(response.standing().remainingStanding()).isEqualTo(15);
		assertThat(response.standing().fare()).isEqualTo((int)(STANDARD_FARE * 0.85));
	}

	@Test
	@DisplayName("일반실 예약이 가능한 경우 입석 정보를 포함하지 않는다")
	void excludeStandingInfo_whenStandardReservable() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(6L, 600, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			50, 80, 24, 24, true, true, 0, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 4, STANDING_RATIO
		);

		// then
		assertThat(response.hasStandingInfo()).isFalse();
		assertThat(response.standing()).isNull();
	}

	@Test
	@DisplayName("입석 요금은 일반실 요금의 85%로 계산된다")
	void calculateStandingFare() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(7L, 700, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			0, 80, 24, 24, false, true, 0, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 2, STANDING_RATIO
		);

		// then
		int expectedFare = (int)(STANDARD_FARE * 0.85);
		assertThat(response.standing().fare()).isEqualTo(expectedFare);

		double discount = (1.0 - (double)response.standing().fare() / STANDARD_FARE) * 100;
		assertThat(discount).isEqualTo(15.0, within(0.1));
	}

	@Test
	@DisplayName("입석 잔여석은 총 좌석의 15%에서 예약된 입석을 뺀 값으로 계산된다")
	void calculateStandingRemaining() {
		// given
		TrainBasicInfo trainInfo = createTrainBasicInfo(8L, 800, "KTX");
		SectionSeatStatus sectionStatus = SectionSeatStatus.of(
			0, 80, 24, 24, false, true, 5, 104
		);
		StationFare fare = createStationFare();

		// when
		TrainSearchResponse response = mapper.toResponse(
			trainInfo, sectionStatus, fare, 3, STANDING_RATIO
		);

		// then
		assertThat(response.standing().remainingStanding()).isEqualTo(10); // 15 - 5
		assertThat(response.standing().maxStanding()).isEqualTo(15); // 104 * 0.15
	}

	private TrainBasicInfo createTrainBasicInfo(Long scheduleId, int trainNumber, String trainName) {
		return new TrainBasicInfo(
			scheduleId, trainNumber, trainName,
			"서울", "부산",
			LocalTime.of(10, 0), LocalTime.of(13, 0)
		);
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
