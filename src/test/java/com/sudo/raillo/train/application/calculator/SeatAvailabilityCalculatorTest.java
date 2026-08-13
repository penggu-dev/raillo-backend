package com.sudo.raillo.train.application.calculator;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 점유 좌석 수는 예약(HELD)과 확정 예매(CONFIRMED)를 합산한 단일 집계값이다.
 */
@ServiceTest
class SeatAvailabilityCalculatorTest {

	@Autowired
	private SeatAvailabilityCalculator calculator;

	@Test
	@DisplayName("일반실 잔여석을 총 좌석에서 점유 좌석을 뺀 값으로 계산한다")
	void calculateStandardSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 50, CarType.FIRST_CLASS, 20);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 10);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(40);
		assertThat(result.standardTotal()).isEqualTo(50);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
		assertThat(result.firstClassTotal()).isEqualTo(20);
	}

	@Test
	@DisplayName("특실 잔여석을 총 좌석에서 점유 좌석을 뺀 값으로 계산한다")
	void calculateFirstClassSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 30);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.FIRST_CLASS, 15);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(80);
		assertThat(result.standardTotal()).isEqualTo(80);
		assertThat(result.firstClassRemaining()).isEqualTo(15);
		assertThat(result.firstClassTotal()).isEqualTo(30);
	}

	@Test
	@DisplayName("점유된 좌석이 없으면 전체 좌석 수와 잔여석이 동일하다")
	void calculateWithNoOccupancy() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 100, CarType.FIRST_CLASS, 40);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(Map.of(), totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(100);
		assertThat(result.firstClassRemaining()).isEqualTo(40);
	}

	@Test
	@DisplayName("모든 좌석이 점유된 경우 잔여석이 0이 된다")
	void calculateWithFullyOccupied() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 50, CarType.FIRST_CLASS, 20);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 50, CarType.FIRST_CLASS, 20);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isZero();
		assertThat(result.firstClassRemaining()).isZero();
	}

	@Test
	@DisplayName("점유 좌석이 전체 좌석보다 많아도 잔여석은 음수가 되지 않는다")
	void neverNegative() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 10, CarType.FIRST_CLASS, 5);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 15);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isNotNegative();
		assertThat(result.firstClassRemaining()).isNotNegative();
	}

	@Test
	@DisplayName("일반실과 특실 점유가 혼합된 경우 각 좌석 타입별로 정확하게 계산한다")
	void calculateWithMixedOccupancy() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 30);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 20, CarType.FIRST_CLASS, 10);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(60);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
	}

	@Test
	@DisplayName("요청 인원보다 일반실과 특실 잔여석이 충분한 경우 모두 예약 가능으로 판단한다")
	void bothReservable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 5);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 4);

		// then
		assertThat(result.standardRemaining()).isEqualTo(75);
		assertThat(result.canReserveStandard()).isTrue();
		assertThat(result.firstClassRemaining()).isEqualTo(24);
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("일반실이 부족하고 특실이 충분한 경우 특실만 예약 가능으로 판단한다")
	void onlyFirstClassReservable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 78);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 5);

		// then
		assertThat(result.standardRemaining()).isEqualTo(2);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.firstClassRemaining()).isEqualTo(24);
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("예약과 확정 예매를 합산한 잔여석이 요청 인원보다 적으면 예약 불가능으로 판단한다")
	void notReservableWhenOccupiedSeatsExceedDemand() {
		// given - 확정 예매 75석 + 예약 3석이 합산되어 78석 점유
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 78);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 4);

		// then
		assertThat(result.standardRemaining()).isEqualTo(2);
		assertThat(result.canReserveStandard()).isFalse();
	}

	@Test
	@DisplayName("좌석이 모두 부족한 경우 전체 매진으로 판단한다")
	void fullySoldOut() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24);
		Map<CarType, Integer> occupiedSeats = Map.of(CarType.STANDARD, 80, CarType.FIRST_CLASS, 24);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(occupiedSeats, totalSeats, 6);

		// then
		assertThat(result.standardRemaining()).isZero();
		assertThat(result.firstClassRemaining()).isZero();
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.canReserveFirstClass()).isFalse();
	}
}
