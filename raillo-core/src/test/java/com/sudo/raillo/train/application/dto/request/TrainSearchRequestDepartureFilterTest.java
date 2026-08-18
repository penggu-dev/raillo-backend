package com.sudo.raillo.train.application.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainSearchRequest getDepartureTimeFilter 테스트")
class TrainSearchRequestDepartureFilterTest {

	@Test
	@DisplayName("미래 날짜 검색 시 요청 시간을 그대로 반환한다")
	void futureDate_returnsRequestTime() {
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, LocalDate.now().plusDays(1), 1, "09"
		);

		assertThat(request.getDepartureTimeFilter()).isEqualTo(LocalTime.of(9, 0));
	}

	@Test
	@DisplayName("당일 검색 시 요청 시간이 예약 마감 기준(현재+5분)보다 이전이면 마감 기준 시간을 반환한다")
	void today_requestTimeBeforeCloseFilter_returnsBookingCloseFilter() {
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, LocalDate.now(), 1, "00"
		);

		LocalTime before = LocalTime.now().plusMinutes(5);
		LocalTime result = request.getDepartureTimeFilter();
		LocalTime after = LocalTime.now().plusMinutes(5);

		assertThat(result).isBetween(before, after);
	}

	@Test
	@DisplayName("당일 검색 시 요청 시간이 예약 마감 기준(현재+5분)보다 이후이면 요청 시간을 그대로 반환한다")
	void today_requestTimeAfterCloseFilter_returnsRequestTime() {
		int futureHour = LocalTime.now().plusHours(2).getHour();
		assumeTrue(futureHour > LocalTime.now().getHour(), "자정 넘김으로 인한 테스트 스킵");

		String departureHour = String.format("%02d", futureHour);
		TrainSearchRequest request = new TrainSearchRequest(
			1L, 2L, LocalDate.now(), 1, departureHour
		);

		assertThat(request.getDepartureTimeFilter()).isEqualTo(LocalTime.of(futureHour, 0));
	}
}
