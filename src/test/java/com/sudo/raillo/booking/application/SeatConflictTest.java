package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
public class SeatConflictTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	@Autowired
	private BookingValidator bookingValidator;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createCustomKTX(2, 1);
		trainScheduleResult = trainScheduleTestHelper.builder()
			.train(train)
			.addStop("서울", null, LocalTime.of(6, 0))                      // stopOrder: 0
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))  // stopOrder: 1
			.addStop("동대구", LocalTime.of(8, 0), LocalTime.of(8, 5)) // stopOrder: 2
			.addStop("부산", LocalTime.of(9, 0), null)                    // stopOrder: 3
			.build();

		// 역간 요금 정보 미리 생성
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 23000, 32000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "동대구", 35000, 49000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 59000, 83000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "동대구", 15000, 21000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 40000, 56000);
		trainScheduleTestHelper.createOrUpdateStationFare("동대구", "부산", 20000, 28000);
	}

	@Nested
	@DisplayName("성공 케이스 - 충돌 없음")
	class NoConflictTests {

		@Test
		@DisplayName("하나의 예약과 하나의 좌석의 같은 스케줄, 같은 좌석에 대한 확정 예매 좌석이 없다면 검증에 통과한다")
		void noExistingSeatBooking_success() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Seat seat = seats.get(0);

			// Redis에 Hold 저장 (서울 -> 대전, "0-1" 구간)
			String pendingBookingId = "pb_123";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat.getId(),
				pendingBookingId,
				0, 1
			);

			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId(pendingBookingId)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatNoException().isThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			);
		}

		@Test
		@DisplayName("여러 개의 예약, 여러 개의 좌석에 대한 확정 예매 좌석이 없다면 검증을 통과한다")
		void multiplePendingBookings_multipleSeats_noExistingSeatBooking_success() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 4);
			Seat seat1 = seats.get(0);
			Seat seat2 = seats.get(1);
			Seat seat3 = seats.get(2);
			Seat seat4 = seats.get(3);

			// [예약1] 서울 -> 대전, 좌석 2개
			String pendingBookingId1 = "pb_1";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat1.getId(),
				pendingBookingId1,
				0, 1
			);
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat2.getId(),
				pendingBookingId1,
				0, 1
			);

			PendingBooking pendingBooking1 = PendingBookingFixture.builder()
				.withId(pendingBookingId1)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat1.getId(), PassengerType.ADULT),
					new PendingSeatBooking(seat2.getId(), PassengerType.ADULT)
				))
				.build();

			// [예약2] 대전 -> 부산, 좌석 2개
			String pendingBookingId2 = "pb_2";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat3.getId(),
				pendingBookingId2,
				1, 3
			);
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat4.getId(),
				pendingBookingId2,
				1, 3
			);

			PendingBooking pendingBooking2 = PendingBookingFixture.builder()
				.withId(pendingBookingId2)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat3.getId(), PassengerType.ADULT),
					new PendingSeatBooking(seat4.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatNoException().isThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking1, pendingBooking2))
			);
		}

		@Test
		@DisplayName("구간이 겹치지 않으면 좌석 충돌 검증을 통과한다")
		void nonOverlappingSections_success() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Seat seat = seats.get(0);

			// [기존 예매] 동대구 -> 부산, "2-3" 구간
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.setDepartureScheduleStop(trainScheduleResult.scheduleStops().get(2))
				.setArrivalScheduleStop(trainScheduleResult.scheduleStops().get(3))
				.addSeat(seat, PassengerType.ADULT)
				.build();

			// [예매하려는 새 예약] 서울 -> 대전, "0-1" 구간
			String pendingBookingId = "pb_123";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat.getId(),
				pendingBookingId,
				0, 1
			);

			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId(pendingBookingId)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatNoException().isThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			);
		}

		@Test
		@DisplayName("인접한 구간은 충돌하지 않는다")
		void adjacentSections_success() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Seat seat = seats.get(0);

			// [기존 예매] 대전 -> 부산, "1-3" 구간
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.setDepartureScheduleStop(trainScheduleResult.scheduleStops().get(1))
				.setArrivalScheduleStop(trainScheduleResult.scheduleStops().get(3))
				.addSeat(seat, PassengerType.ADULT)
				.build();

			// [예매하려는 새 예약] 서울 -> 대전, "0-1" 구간
			String pendingBookingId = "pb_123";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat.getId(),
				pendingBookingId,
				0, 1
			);

			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId(pendingBookingId)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatNoException().isThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			);
		}
	}

	@Nested
	@DisplayName("실패 케이스 - 충돌 발생")
	class ConflictTest {

		@Test
		@DisplayName("하나의 예약, 하나의 좌석에 대해 확정 예매 좌석과 구간이 겹치면 예외가 발생한다")
		void overlappingSections_fail() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Seat seat = seats.get(0);

			// [기존 예매] 대전 -> 부산, "1-3" 구간
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.setDepartureScheduleStop(trainScheduleResult.scheduleStops().get(1))
				.setArrivalScheduleStop(trainScheduleResult.scheduleStops().get(3))
				.addSeat(seat, PassengerType.ADULT)
				.build();

			// [예매하려는 새 예약] 서울 -> 동대구, "0-2" 구간 (대전-동대구 구간 겹침)
			String pendingBookingId = "pb_123";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat.getId(),
				pendingBookingId,
				0, 2
			);

			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId(pendingBookingId)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			).isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_BOOKED)
				.hasMessage(BookingError.SEAT_ALREADY_BOOKED.getMessage());
		}

		@Test
		@DisplayName("하나의 예약, 여러 개의 좌석 중 하나라도 충돌하면 예외가 발생한다")
		void multipleSeats_oneConflict_fail() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
			Seat seat1 = seats.get(0);
			Seat seat2 = seats.get(1);

			// seat1은 기존 예매 좌석으로 생성. 서울 -> 동대구, "0-2" 구간
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.setDepartureScheduleStop(trainScheduleResult.scheduleStops().get(0))
				.setArrivalScheduleStop(trainScheduleResult.scheduleStops().get(2))
				.addSeat(seat1, PassengerType.ADULT)
				.build();

			String pendingBookingId = "pb_123";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat1.getId(),
				pendingBookingId,
				0, 2  // seat1은 충돌
			);
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat2.getId(),
				pendingBookingId,
				0, 2  // seat2는 충돌 없음
			);

			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId(pendingBookingId)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat1.getId(), PassengerType.ADULT),
					new PendingSeatBooking(seat2.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			).isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_BOOKED)
				.hasMessage(BookingError.SEAT_ALREADY_BOOKED.getMessage());
		}

		@Test
		@DisplayName("여러 개의 예약, 여러 개의 좌석 중 하나라도 충돌이 나면 예외가 발생한다")
		void multiplePendingBookings_multipleSeats_oneConflict_fail() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 4);
			Seat seat1 = seats.get(0);
			Seat seat2 = seats.get(1);
			Seat seat3 = seats.get(2);
			Seat seat4 = seats.get(3);

			// [기존 예매] 대전 -> 부산, "1-3" 구간, seat3 예매
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.setDepartureScheduleStop(trainScheduleResult.scheduleStops().get(1))
				.setArrivalScheduleStop(trainScheduleResult.scheduleStops().get(3))
				.addSeat(seat3, PassengerType.ADULT)
				.build();

			// [예약1] 서울 -> 대전, "0-1" 구간, 좌석 2개 - 충돌 없음
			String pendingBookingId1 = "pb_1";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat1.getId(),
				pendingBookingId1,
				0, 1
			);
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat2.getId(),
				pendingBookingId1,
				0, 1
			);

			PendingBooking pendingBooking1 = PendingBookingFixture.builder()
				.withId(pendingBookingId1)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat1.getId(), PassengerType.ADULT),
					new PendingSeatBooking(seat2.getId(), PassengerType.ADULT)
				))
				.build();

			// [예약2] 서울 -> 부산, "0-3" 구간, 좌석 2개 중 seat3이 "1-3" 구간과 충돌
			String pendingBookingId2 = "pb_2";
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat3.getId(),
				pendingBookingId2,
				0, 3  // 기존 "1-3"과 겹침
			);
			seatHoldRepository.tryHold(
				trainScheduleResult.trainSchedule().getId(),
				seat4.getId(),
				pendingBookingId2,
				0, 3  // 충돌 없음
			);

			PendingBooking pendingBooking2 = PendingBookingFixture.builder()
				.withId(pendingBookingId2)
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat3.getId(), PassengerType.ADULT),
					new PendingSeatBooking(seat4.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking1, pendingBooking2))
			).isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_BOOKED)
				.hasMessage(BookingError.SEAT_ALREADY_BOOKED.getMessage());
		}

		@Test
		@DisplayName("Hold 구간 조회 시 점유 구간 조회에 실패하면 예외가 발생한다")
		void noHoldSection_fail() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Seat seat = seats.get(0);

			// 기존 예매 생성
			BookingResult existingBooking = bookingTestHelper.builder(member, trainScheduleResult)
				.addSeat(seat, PassengerType.ADULT)
				.build();

			// Redis에 Hold 없이 PendingBooking 생성
			PendingBooking pendingBooking = PendingBookingFixture.builder()
				.withId("pb_123")
				.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
				.withPendingSeatBookings(List.of(
					new PendingSeatBooking(seat.getId(), PassengerType.ADULT)
				))
				.build();

			// when & then
			assertThatThrownBy(() ->
				bookingValidator.validateSeatConflicts(List.of(pendingBooking))
			).isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_HOLD_SECTION_NOT_FOUND)
				.hasMessage(BookingError.SEAT_HOLD_SECTION_NOT_FOUND.getMessage());
		}
	}
}
