package com.sudo.raillo.train.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.support.fixture.train.ScheduleStopFixture;
import com.sudo.raillo.support.fixture.train.TrainScheduleFixture;
import com.sudo.raillo.train.domain.status.OperationStatus;

@DisplayName("TrainSchedule - getDepartureDateTimeAt 테스트")
class TrainScheduleTest {

	@Test
	@DisplayName("정차역 출발시간이 열차 출발시간 이후이면 같은 날짜를 반환한다")
	void sameDayDeparture() {
		// given - 열차 출발 05:00, 정차역 출발 07:00
		LocalDate operationDate = LocalDate.of(2026, 1, 1);
		TrainSchedule schedule = TrainScheduleFixture.create(
			"KTX 001", operationDate,
			LocalTime.of(5, 0), LocalTime.of(9, 0),
			OperationStatus.ACTIVE, null, null, null
		);

		ScheduleStop stop = ScheduleStopFixture.create(
			1, LocalTime.of(6, 50), LocalTime.of(7, 0), schedule, null
		);

		// when
		LocalDateTime result = schedule.getDepartureDateTimeAt(stop);

		// then
		assertThat(result).isEqualTo(LocalDateTime.of(2026, 1, 1, 7, 0));
	}

	@Test
	@DisplayName("정차역 출발시간이 열차 출발시간보다 이르면 자정을 넘긴 것이므로 다음날을 반환한다")
	void nextDayDeparture_whenStopTimeBeforeTrainDepartureTime() {
		// given - 열차 출발 23:00, 정차역 출발 01:00 (자정 경과)
		LocalDate operationDate = LocalDate.of(2026, 1, 1);
		TrainSchedule schedule = TrainScheduleFixture.create(
			"KTX 001", operationDate,
			LocalTime.of(23, 0), LocalTime.of(2, 0),
			OperationStatus.ACTIVE, null, null, null
		);

		ScheduleStop stop = ScheduleStopFixture.create(
			1, LocalTime.of(0, 50), LocalTime.of(1, 0), schedule, null
		);

		// when
		LocalDateTime result = schedule.getDepartureDateTimeAt(stop);

		// then
		assertThat(result).isEqualTo(LocalDateTime.of(2026, 1, 2, 1, 0));
	}

	@Test
	@DisplayName("정차역 출발시간이 열차 출발시간과 같으면 같은 날짜를 반환한다")
	void sameDayDeparture_whenSameTime() {
		// given - 열차 출발 05:00, 정차역(출발역) 출발 05:00
		LocalDate operationDate = LocalDate.of(2026, 1, 1);
		TrainSchedule schedule = TrainScheduleFixture.create(
			"KTX 001", operationDate,
			LocalTime.of(5, 0), LocalTime.of(9, 0),
			OperationStatus.ACTIVE, null, null, null
		);

		ScheduleStop stop = ScheduleStopFixture.create(
			0, null, LocalTime.of(5, 0), schedule, null
		);

		// when
		LocalDateTime result = schedule.getDepartureDateTimeAt(stop);

		// then
		assertThat(result).isEqualTo(LocalDateTime.of(2026, 1, 1, 5, 0));
	}
}
