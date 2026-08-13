package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import org.assertj.core.api.ThrowableAssert;

import com.sudo.raillo.booking.application.service.SeatOccupancyService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 좌석 충돌 검증 테스트
 *
 * <p>예약(HELD)과 확정 예매(CONFIRMED)가 {@code seat_occupancy} 한 테이블을 공유하므로
 * 두 종류의 충돌이 하나의 검증으로 판정된다.</p>
 */
@ServiceTest
@DisplayName("좌석 충돌 검증")
public class SeatConflictValidatorTest {

	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private BookingValidator bookingValidator;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private SeatOccupancyService seatOccupancyService;
	@Autowired
	private TrainTestHelper trainTestHelper;
	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;
	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private ScheduleStop seoul;
	private ScheduleStop daejeon;
	private ScheduleStop dongdaegu;
	private ScheduleStop busan;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createCustomKTX(2, 1);
		trainScheduleResult = trainScheduleTestHelper.builder()
			.train(train)
			.addStop("서울", null, LocalTime.of(6, 0))                 // stopOrder: 0
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))    // stopOrder: 1
			.addStop("동대구", LocalTime.of(8, 0), LocalTime.of(8, 5))  // stopOrder: 2
			.addStop("부산", LocalTime.of(9, 0), null)                 // stopOrder: 3
			.build();

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 23000, 32000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "동대구", 35000, 49000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 59000, 83000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "동대구", 15000, 21000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 40000, 56000);
		trainScheduleTestHelper.createOrUpdateStationFare("동대구", "부산", 20000, 28000);

		List<ScheduleStop> stops = trainScheduleResult.scheduleStops();
		seoul = stops.get(0);
		daejeon = stops.get(1);
		dongdaegu = stops.get(2);
		busan = stops.get(3);
	}

	@Nested
	@DisplayName("충돌 없음")
	class NoConflictTests {

		@Test
		@DisplayName("점유된 좌석이 없으면 검증을 통과한다")
		void noOccupancy_success() {
			// given
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

			// when & then
			assertThatNoException().isThrownBy(() ->
				validateSeatConflicts(seoul, busan, List.of(seat.getId())));
		}

		@Test
		@DisplayName("인접하지만 겹치지 않는 구간이면 검증을 통과한다")
		void adjacentSection_success() {
			// given - 서울(0) -> 대전(1) 확정 예매
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			createBooking(seoul, daejeon, seat);

			// when & then - 대전(1) -> 부산(3) 은 구간이 겹치지 않는다
			assertThatNoException().isThrownBy(() ->
				validateSeatConflicts(daejeon, busan, List.of(seat.getId())));
		}

		@Test
		@DisplayName("다른 좌석이 점유되어 있어도 요청 좌석이 비어 있으면 검증을 통과한다")
		void otherSeatOccupied_success() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
			createBooking(seoul, busan, seats.get(0));

			// when & then
			assertThatNoException().isThrownBy(() ->
				validateSeatConflicts(seoul, busan, List.of(seats.get(1).getId())));
		}

		@Test
		@DisplayName("만료된 예약 점유는 충돌로 판정하지 않는다")
		void expiredOccupancy_success() {
			// given
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			holdSeats(seoul, busan, List.of(seat), LocalDateTime.now().minusMinutes(1));

			// when & then
			assertThatNoException().isThrownBy(() ->
				validateSeatConflicts(seoul, busan, List.of(seat.getId())));
		}
	}

	@Nested
	@DisplayName("충돌 발생")
	class ConflictTests {

		@Test
		@DisplayName("확정 예매와 완전히 동일한 구간을 요청하면 예외가 발생한다")
		void sameSection_conflict() {
			// given
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			createBooking(seoul, busan, seat);

			// when & then
			assertSeatAlreadyOccupied(() -> validateSeatConflicts(seoul, busan, List.of(seat.getId())));
		}

		@Test
		@DisplayName("확정 예매와 구간이 일부만 겹쳐도 예외가 발생한다")
		void partialOverlap_conflict() {
			// given - 서울(0) -> 동대구(2) 확정 예매
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			createBooking(seoul, dongdaegu, seat);

			// when & then - 대전(1) -> 부산(3) 은 구간 1에서 겹친다
			assertSeatAlreadyOccupied(() -> validateSeatConflicts(daejeon, busan, List.of(seat.getId())));
		}

		@Test
		@DisplayName("여러 좌석 중 하나라도 점유되어 있으면 예외가 발생한다")
		void oneOfManySeats_conflict() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);
			createBooking(seoul, busan, seats.get(1));

			// when & then
			assertSeatAlreadyOccupied(() -> validateSeatConflicts(
				seoul, busan, seats.stream().map(Seat::getId).toList()));
		}

		@Test
		@DisplayName("다른 사용자가 점유중인 예약과 겹치면 예외가 발생한다")
		void heldByOtherReservation_conflict() {
			// given - 확정되지 않은 예약(HELD) 점유
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			holdSeats(seoul, busan, List.of(seat), LocalDateTime.now().plusMinutes(10));

			// when & then
			assertSeatAlreadyOccupied(() -> validateSeatConflicts(seoul, busan, List.of(seat.getId())));
		}

		@Test
		@DisplayName("동일 좌석에 여러 예매가 있을 때 그중 하나라도 겹치면 예외가 발생한다")
		void oneOfMultipleBookings_conflict() {
			// given - 서울(0)->대전(1), 동대구(2)->부산(3) 두 구간이 확정 예매됨
			Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
			createBooking(seoul, daejeon, seat);
			createBooking(dongdaegu, busan, seat);

			// when & then - 대전(1) -> 부산(3) 은 뒤쪽 예매와 겹친다
			assertSeatAlreadyOccupied(() -> validateSeatConflicts(daejeon, busan, List.of(seat.getId())));
		}
	}

	// ===== Helper =====

	private void validateSeatConflicts(ScheduleStop departureStop, ScheduleStop arrivalStop, List<Long> seatIds) {
		bookingValidator.validateSeatConflicts(
			trainScheduleResult.trainSchedule().getId(), departureStop, arrivalStop, seatIds);
	}

	private void assertSeatAlreadyOccupied(ThrowableAssert.ThrowingCallable callable) {
		assertThatThrownBy(callable)
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_OCCUPIED)
			.hasMessage(BookingError.SEAT_ALREADY_OCCUPIED.getMessage());
	}

	private void createBooking(ScheduleStop departureStop, ScheduleStop arrivalStop, Seat seat) {
		bookingTestHelper.builder(member, trainScheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeat(seat, PassengerType.ADULT)
			.build();
	}

	private void holdSeats(
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Seat> seats,
		LocalDateTime expiresAt
	) {
		Reservation reservation = reservationRepository.save(Reservation.create(
			"PB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
			member.getMemberDetail().getMemberNo(),
			trainScheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			BigDecimal.valueOf(50000),
			expiresAt
		));
		seatOccupancyService.hold(reservation, seats);
	}
}
