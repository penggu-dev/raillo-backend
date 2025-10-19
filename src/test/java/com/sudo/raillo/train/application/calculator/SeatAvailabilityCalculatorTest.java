package com.sudo.raillo.train.application.calculator;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.train.application.calculator.SeatAvailabilityCalculator.SeatCalculationResult;
import com.sudo.raillo.train.application.dto.SeatReservationInfo;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class SeatAvailabilityCalculatorTest {

	@Autowired
	private SeatAvailabilityCalculator calculator;

	@Test
	@DisplayName("일반실 좌석 계산: 50석 중 10석 예약 → 40석 남음")
	void calculateStandardSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 50,
			CarType.FIRST_CLASS, 20
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.STANDARD, 10);

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, reservations);

		// then
		assertThat(result.standardRemaining()).isEqualTo(40);
		assertThat(result.standardTotal()).isEqualTo(50);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
		assertThat(result.firstClassTotal()).isEqualTo(20);
	}

	@Test
	@DisplayName("특실 좌석 계산: 30석 중 15석 예약 → 15석 남음")
	void calculateFirstClassSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 30
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.FIRST_CLASS, 15);

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, reservations);

		// then
		assertThat(result.standardRemaining()).isEqualTo(80);
		assertThat(result.standardTotal()).isEqualTo(80);
		assertThat(result.firstClassRemaining()).isEqualTo(15);
		assertThat(result.firstClassTotal()).isEqualTo(30);
	}

	@Test
	@DisplayName("예약이 없을 때 전체 좌석 수와 잔여석이 동일하다")
	void calculateWithNoReservations() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 100,
			CarType.FIRST_CLASS, 40
		);
		List<SeatReservationInfo> reservations = List.of();

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, reservations);

		// then
		assertThat(result.standardRemaining()).isEqualTo(100);
		assertThat(result.standardTotal()).isEqualTo(100);
		assertThat(result.firstClassRemaining()).isEqualTo(40);
		assertThat(result.firstClassTotal()).isEqualTo(40);
	}

	@Test
	@DisplayName("전체 예약 시 잔여석이 0이 된다")
	void calculateWithFullReservations() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 50,
			CarType.FIRST_CLASS, 20
		);

		List<SeatReservationInfo> standardReservations = createReservations(CarType.STANDARD, 50);
		List<SeatReservationInfo> firstClassReservations = createReservations(CarType.FIRST_CLASS, 20);

		List<SeatReservationInfo> allReservations = new ArrayList<>();
		allReservations.addAll(standardReservations);
		allReservations.addAll(firstClassReservations);

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, allReservations);

		// then
		assertThat(result.standardRemaining()).isEqualTo(0);
		assertThat(result.firstClassRemaining()).isEqualTo(0);
	}

	@Test
	@DisplayName("예약 수가 전체 좌석보다 많아도 음수가 되지 않는다")
	void neverNegative() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 10,
			CarType.FIRST_CLASS, 5
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.STANDARD, 15);

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, reservations);

		// then
		assertThat(result.standardRemaining()).isGreaterThanOrEqualTo(0);
		assertThat(result.firstClassRemaining()).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("일반실과 특실 혼합 예약 시 각각 정확하게 계산된다")
	void calculateWithMixedReservations() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 30
		);

		List<SeatReservationInfo> standardReservations = createReservations(CarType.STANDARD, 20);
		List<SeatReservationInfo> firstClassReservations = createReservations(CarType.FIRST_CLASS, 10);

		List<SeatReservationInfo> allReservations = new java.util.ArrayList<>();
		allReservations.addAll(standardReservations);
		allReservations.addAll(firstClassReservations);

		// when
		SeatCalculationResult result = calculator.calculateRemainingSeats(totalSeats, allReservations);

		// then
		assertThat(result.standardRemaining()).isEqualTo(60);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
	}

	@Test
	@DisplayName("일반실 예약 가능: 충분한 잔여석이 있을 때")
	void standardReservable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.STANDARD, 5);
		int totalSeatCount = 104;
		int standingReservations = 0;
		int requestedPassengerCount = 4;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			reservations, totalSeats, totalSeatCount, standingReservations, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(75);
		assertThat(result.canReserveStandard()).isTrue();
		assertThat(result.firstClassRemaining()).isEqualTo(24);
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("특실만 예약 가능: 일반실 부족, 특실 충분")
	void onlyFirstClassReservable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.STANDARD, 78);
		int totalSeatCount = 104;
		int standingReservations = 0;
		int requestedPassengerCount = 5;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			reservations, totalSeats, totalSeatCount, standingReservations, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(2);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.firstClassRemaining()).isEqualTo(24);
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("입석만 가능: 일반실 매진, 입석 잔여")
	void standingOnlyAvailable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatReservationInfo> standardReservations = createReservations(CarType.STANDARD, 80);
		List<SeatReservationInfo> firstClassReservations = createReservations(CarType.FIRST_CLASS, 24);

		List<SeatReservationInfo> allReservations = new ArrayList<>();
		allReservations.addAll(standardReservations);
		allReservations.addAll(firstClassReservations);

		int totalSeatCount = 104;
		int standingReservations = 0;
		int requestedPassengerCount = 3;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			allReservations, totalSeats, totalSeatCount, standingReservations, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(0);
		assertThat(result.firstClassRemaining()).isEqualTo(0);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.canReserveFirstClass()).isFalse();

		double standingRatio = calculator.getStandingRatio();
		int maxStanding = (int)(totalSeatCount * standingRatio);
		assertThat(maxStanding - standingReservations).isGreaterThanOrEqualTo(requestedPassengerCount);
	}

	@Test
	@DisplayName("전체 매진: 좌석과 입석 모두 부족")
	void fullySoldOut() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatReservationInfo> standardReservations = createReservations(CarType.STANDARD, 80);
		List<SeatReservationInfo> firstClassReservations = createReservations(CarType.FIRST_CLASS, 24);

		List<SeatReservationInfo> allReservations = new java.util.ArrayList<>();
		allReservations.addAll(standardReservations);
		allReservations.addAll(firstClassReservations);

		int totalSeatCount = 104;
		int standingReservations = 15;
		int requestedPassengerCount = 6;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			allReservations, totalSeats, totalSeatCount, standingReservations, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(0);
		assertThat(result.firstClassRemaining()).isEqualTo(0);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.canReserveFirstClass()).isFalse();

		double standingRatio = calculator.getStandingRatio();
		assertThat(result.getStandingRemaining(standingRatio)).isEqualTo(0);
	}

	@Test
	@DisplayName("입석 잔여 계산: 총 좌석의 15% 계산")
	void calculateStandingRemaining() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatReservationInfo> reservations = createReservations(CarType.STANDARD, 80);
		int totalSeatCount = 104;
		int standingReservations = 5;
		int requestedPassengerCount = 3;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			reservations, totalSeats, totalSeatCount, standingReservations, requestedPassengerCount
		);

		// then
		double standingRatio = calculator.getStandingRatio();
		int expectedMaxStanding = (int)(totalSeatCount * standingRatio);
		int expectedRemaining = expectedMaxStanding - standingReservations;

		assertThat(result.getMaxStandingCapacity(standingRatio)).isEqualTo(expectedMaxStanding);
		assertThat(result.getStandingRemaining(standingRatio)).isEqualTo(expectedRemaining);
	}

	private List<SeatReservationInfo> createReservations(CarType carType, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> new SeatReservationInfo(
				(long) i + 1,        // seatId
				carType,             // carType
				1L,                  // departureStationId (더미)
				2L                   // arrivalStationId (더미)
			))
			.toList();
	}
}
