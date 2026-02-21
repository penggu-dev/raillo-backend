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
import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class SeatAvailabilityCalculatorTest {

	@Autowired
	private SeatAvailabilityCalculator calculator;

	@Test
	@DisplayName("일반실 좌석 잔여석을 총 좌석에서 확정 좌석을 뺀 값으로 계산한다")
	void calculateStandardSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 50,
			CarType.FIRST_CLASS, 20
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 10);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(bookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(40);
		assertThat(result.standardTotal()).isEqualTo(50);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
		assertThat(result.firstClassTotal()).isEqualTo(20);
	}

	@Test
	@DisplayName("특실 좌석 잔여석을 총 좌석에서 확정 좌석을 뺀 값으로 계산한다")
	void calculateFirstClassSeats() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 30
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.FIRST_CLASS, 15);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(bookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(80);
		assertThat(result.standardTotal()).isEqualTo(80);
		assertThat(result.firstClassRemaining()).isEqualTo(15);
		assertThat(result.firstClassTotal()).isEqualTo(30);
	}

	@Test
	@DisplayName("예매가 없는 경우 전체 좌석 수와 잔여석이 동일하다")
	void calculateWithNoBookings() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 100,
			CarType.FIRST_CLASS, 40
		);
		List<SeatBookingInfo> bookings = List.of();

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(bookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(100);
		assertThat(result.standardTotal()).isEqualTo(100);
		assertThat(result.firstClassRemaining()).isEqualTo(40);
		assertThat(result.firstClassTotal()).isEqualTo(40);
	}

	@Test
	@DisplayName("모든 좌석이 예매된 경우 잔여석이 0이 된다")
	void calculateWithFullBookings() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 50,
			CarType.FIRST_CLASS, 20
		);

		List<SeatBookingInfo> allBookings = new ArrayList<>();
		allBookings.addAll(createBookings(CarType.STANDARD, 50));
		allBookings.addAll(createBookings(CarType.FIRST_CLASS, 20));

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(allBookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(0);
		assertThat(result.firstClassRemaining()).isEqualTo(0);
	}

	@Test
	@DisplayName("예매 수가 전체 좌석보다 많아도 잔여석은 음수가 되지 않는다")
	void neverNegative() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 10,
			CarType.FIRST_CLASS, 5
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 15);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(bookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isGreaterThanOrEqualTo(0);
		assertThat(result.firstClassRemaining()).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("일반실과 특실 예매가 혼합된 경우 각 좌석 타입별로 정확하게 계산한다")
	void calculateWithMixedBookings() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 30
		);

		List<SeatBookingInfo> allBookings = new ArrayList<>();
		allBookings.addAll(createBookings(CarType.STANDARD, 20));
		allBookings.addAll(createBookings(CarType.FIRST_CLASS, 10));

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(allBookings, totalSeats, Map.of(), 1);

		// then
		assertThat(result.standardRemaining()).isEqualTo(60);
		assertThat(result.firstClassRemaining()).isEqualTo(20);
	}

	@Test
	@DisplayName("요청 인원보다 일반실과 특실 잔여석이 충분한 경우 모두 예약 가능으로 판단한다")
	void standardReservable() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 5);
		int requestedPassengerCount = 4;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			bookings, totalSeats, Map.of(), requestedPassengerCount
		);

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
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 78);
		int requestedPassengerCount = 5;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			bookings, totalSeats, Map.of(), requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(2);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.firstClassRemaining()).isEqualTo(24);
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("Hold 점유가 있으면 잔여석에서 차감된다")
	void holdSeatsReduceRemaining() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 10);
		Map<CarType, Integer> holdSeats = Map.of(
			CarType.STANDARD, 5,
			CarType.FIRST_CLASS, 3
		);

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			bookings, totalSeats, holdSeats, 1
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(65); // 80 - 10 - 5 = 65
		assertThat(result.firstClassRemaining()).isEqualTo(21); // 24 - 0 - 3 = 21
	}

	@Test
	@DisplayName("확정 좌석과 예약 좌석을 합산한 남은 좌석이 부족하면 예약 불가능으로 판단한다")
	void notReservableWithBookingAndHold() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);
		List<SeatBookingInfo> bookings = createBookings(CarType.STANDARD, 75);
		Map<CarType, Integer> holdSeats = Map.of(
			CarType.STANDARD, 3
		);
		int requestedPassengerCount = 4;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			bookings, totalSeats, holdSeats, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(2); // 80 - 75 - 3 = 2
		assertThat(result.canReserveStandard()).isFalse(); // 요청 4명 > 잔여 2석
	}

	@Test
	@DisplayName("확정 좌석과 예약 좌석이 혼합된 경우 CarType별로 정확하게 잔여석을 계산한다")
	void calculateWithBookingAndHoldMixed() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);

		List<SeatBookingInfo> allBookings = new ArrayList<>();
		allBookings.addAll(createBookings(CarType.STANDARD, 30));
		allBookings.addAll(createBookings(CarType.FIRST_CLASS, 10));

		Map<CarType, Integer> holdSeats = Map.of(
			CarType.STANDARD, 10,
			CarType.FIRST_CLASS, 4
		);
		int requestedPassengerCount = 5;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			allBookings, totalSeats, holdSeats, requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(40); // 80 - 30 - 10 = 40
		assertThat(result.firstClassRemaining()).isEqualTo(10); // 24 - 10 - 4 = 10
		assertThat(result.canReserveStandard()).isTrue();
		assertThat(result.canReserveFirstClass()).isTrue();
	}

	@Test
	@DisplayName("좌석이 모두 부족한 경우 전체 매진으로 판단한다")
	void fullySoldOut() {
		// given
		Map<CarType, Integer> totalSeats = Map.of(
			CarType.STANDARD, 80,
			CarType.FIRST_CLASS, 24
		);

		List<SeatBookingInfo> allBookings = new ArrayList<>();
		allBookings.addAll(createBookings(CarType.STANDARD, 80));
		allBookings.addAll(createBookings(CarType.FIRST_CLASS, 24));

		int requestedPassengerCount = 6;

		// when
		SectionSeatStatus result = calculator.calculateSectionSeatStatus(
			allBookings, totalSeats, Map.of(), requestedPassengerCount
		);

		// then
		assertThat(result.standardRemaining()).isEqualTo(0);
		assertThat(result.firstClassRemaining()).isEqualTo(0);
		assertThat(result.canReserveStandard()).isFalse();
		assertThat(result.canReserveFirstClass()).isFalse();
	}

	private List<SeatBookingInfo> createBookings(CarType carType, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> new SeatBookingInfo(
				(long) i + 1,        // seatId
				carType,             // carType
				1L,                  // departureStationId (더미)
				2L                   // arrivalStationId (더미)
			))
			.toList();
	}
}
