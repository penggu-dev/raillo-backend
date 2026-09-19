package com.sudo.raillo.booking.application.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.fixture.ReservationFixture;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;
import com.sudo.raillo.train.exception.TrainError;

@DisplayName("ReservationValidator - 예약 규칙 검증")
class ReservationValidatorTest {

	private final ReservationValidator validator = new ReservationValidator();

	private static ScheduleInfoCacheValue schedule(OperationStatus status) {
		return new ScheduleInfoCacheValue(1L, LocalDate.of(2026, 10, 20), LocalTime.of(6, 0), LocalTime.of(9, 0),
			status, 0, 7L, 101, "KTX", 1L, 5L);
	}

	private static ScheduleStopCacheValue stop(int stopOrder) {
		return new ScheduleStopCacheValue(9000L + stopOrder, stopOrder, 100L + stopOrder, "역" + stopOrder,
			LocalTime.of(6 + stopOrder, 0), LocalTime.of(6 + stopOrder, 5));
	}

	private static SeatCacheValue seat(long trainCarId, CarType carType) {
		return new SeatCacheValue(trainCarId, 3, carType, 1, "A", SeatType.WINDOW);
	}

	@Nested
	@DisplayName("운행과 구간")
	class ScheduleRules {

		@Test
		@DisplayName("운행이 취소된 스케줄이면 TRAIN_OPERATION_CANCELLED 예외가 발생한다")
		void cancelled_schedule() {
			// given
			ScheduleInfoCacheValue cancelled = schedule(OperationStatus.CANCELLED);

			// when

			// then
			assertThatThrownBy(() -> validator.validateOperating(cancelled))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.TRAIN_OPERATION_CANCELLED);
			assertThatCode(() -> validator.validateOperating(schedule(OperationStatus.DELAYED))).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("구간 운임이 없으면 STATION_FARE_NOT_FOUND 예외가 발생하고 있으면 그 운임을 돌려준다")
		void fare_exists() {
			// given
			StationFareCacheValue fare = new StationFareCacheValue(new BigDecimal("59800"), new BigDecimal("83700"));

			// when
			StationFareCacheValue validated = validator.validateFareExists(fare);

			// then
			assertThat(validated).isEqualTo(fare);
			assertThatThrownBy(() -> validator.validateFareExists(null))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.STATION_FARE_NOT_FOUND);
		}

		@Test
		@DisplayName("출발 정차역이 도착 정차역보다 앞서지 않으면 INVALID_ROUTE 예외가 발생한다")
		void stop_sequence() {
			// given

			// when

			// then
			assertThatCode(() -> validator.validateStopSequence(stop(0), stop(2))).doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validateStopSequence(stop(2), stop(2)))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.INVALID_ROUTE);
			assertThatThrownBy(() -> validator.validateStopSequence(stop(3), stop(1)))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.INVALID_ROUTE);
		}

		@Test
		@DisplayName("출발 5분 전부터는 예약이 마감되어 DEPARTURE_TIME_PASSED 예외가 발생한다")
		void booking_close_boundary() {
			// given
			LocalDateTime departureAt = LocalDateTime.of(2026, 10, 20, 9, 0);
			LocalDateTime closeAt = Reservation.bookingCloseAt(departureAt);

			// when

			// then
			assertThatCode(() -> validator.validateBookingOpen(departureAt, closeAt.minusSeconds(1)))
				.doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validateBookingOpen(departureAt, closeAt))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
			assertThatThrownBy(() -> validator.validateBookingOpen(departureAt, departureAt.plusMinutes(1)))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
		}
	}

	@Nested
	@DisplayName("승객과 좌석")
	class SeatRules {

		@Test
		@DisplayName("승객 수와 좌석 수가 다르면 BOOKING_CREATE_SEATS_INVALID 예외가 발생한다")
		void passenger_seat_count() {
			// given
			List<PassengerType> twoPassengers = List.of(PassengerType.ADULT, PassengerType.CHILD);

			// when

			// then
			assertThatCode(() -> validator.validatePassengerSeatCount(twoPassengers, List.of(1L, 2L)))
				.doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validatePassengerSeatCount(twoPassengers, List.of(1L)))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.BOOKING_CREATE_SEATS_INVALID);
		}

		@Test
		@DisplayName("같은 좌석을 두 번 고르면 DUPLICATE_SEAT_IDS 예외가 발생한다")
		void duplicate_seats() {
			// given

			// when

			// then
			assertThatCode(() -> validator.validateDistinctSeats(List.of(1L, 2L))).doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validateDistinctSeats(List.of(1L, 1L)))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.DUPLICATE_SEAT_IDS);
		}

		@Test
		@DisplayName("좌석이 모두 한 객차에 있으면 그 객차 타입을 돌려주고 두 객차에 걸치면 MULTIPLE_TRAIN_CARS 예외가 발생한다")
		void single_train_car() {
			// given
			List<SeatCacheValue> sameCar = List.of(seat(231L, CarType.FIRST_CLASS), seat(231L, CarType.FIRST_CLASS));
			List<SeatCacheValue> twoCarsOfSameType = List.of(seat(231L, CarType.STANDARD), seat(232L, CarType.STANDARD));

			// when
			CarType carType = validator.validateSingleTrainCar(sameCar);

			// then
			assertThat(carType).isEqualTo(CarType.FIRST_CLASS);
			assertThatThrownBy(() -> validator.validateSingleTrainCar(twoCarsOfSameType))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.MULTIPLE_TRAIN_CARS);
		}
	}

	@Nested
	@DisplayName("예약 조회")
	class LookupRules {

		@Test
		@DisplayName("예약 ID 목록이 비어 있으면 RESERVATION_IDS_REQUIRED 예외가 발생한다")
		void ids_required() {
			// given

			// when

			// then
			assertThatThrownBy(() -> validator.validateReservationIdsPresent(List.of()))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_IDS_REQUIRED);
		}

		@Test
		@DisplayName("요청한 예약 중 하나라도 없으면 RESERVATION_EXPIRED 예외가 발생한다")
		void all_exist() {
			// given
			Map<String, Long> found = Map.of("RV1", 1L);

			// when

			// then
			assertThatCode(() -> validator.validateAllReservationsExist(List.of("RV1"), found)).doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validateAllReservationsExist(List.of("RV1", "RV2"), found))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}

		@Test
		@DisplayName("다른 회원의 예약이면 RESERVATION_ACCESS_DENIED 예외가 발생한다")
		void owner() {
			// given
			Reservation reservation = ReservationFixture.builder().withMemberNo("202507300001").build();

			// when

			// then
			assertThatCode(() -> validator.validateOwner(reservation, "202507300001")).doesNotThrowAnyException();
			assertThatThrownBy(() -> validator.validateOwner(reservation, "202507300002"))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_ACCESS_DENIED);
		}
	}
}
