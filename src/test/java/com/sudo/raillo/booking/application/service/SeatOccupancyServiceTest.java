package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatOccupancy;
import com.sudo.raillo.booking.domain.status.SeatOccupancyStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
@DisplayName("SeatOccupancyService 좌석 점유")
class SeatOccupancyServiceTest {

	private static final String MEMBER_NO = "202601010001";

	@Autowired
	private SeatOccupancyService seatOccupancyService;
	@Autowired
	private SeatOccupancyRepository seatOccupancyRepository;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private TrainTestHelper trainTestHelper;
	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;
	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Train train;
	private TrainScheduleResult scheduleResult;
	private ScheduleStop seoul;
	private ScheduleStop daejeon;
	private ScheduleStop dongdaegu;
	private ScheduleStop busan;

	@BeforeEach
	void setUp() {
		// 서울(0) -> 대전(1) -> 동대구(2) -> 부산(3)
		train = trainTestHelper.createSmallTestTrain();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);

		scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(6, 0), LocalTime.of(6, 5))
			.addStop("동대구", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(8, 0), null)
			.build();

		seoul = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		daejeon = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "대전");
		dongdaegu = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "동대구");
		busan = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
	}

	@Nested
	@DisplayName("좌석 점유(hold)")
	class HoldTest {

		@Test
		@DisplayName("좌석을 점유하면 좌석 수 x 구간 수 만큼의 점유 행이 생성된다")
		void hold_creates_one_row_per_seat_and_section() {
			// given - 서울(0) -> 부산(3), 3개 구간
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
			Reservation reservation = createReservation(seoul, busan, futureExpiry());

			// when
			seatOccupancyService.hold(reservation, seats);

			// then
			List<SeatOccupancy> occupancies = seatOccupancyRepository.findAllByReservationId(reservation.getId());
			assertThat(occupancies).hasSize(6); // 좌석 2개 x 구간 3개
			assertThat(occupancies).allSatisfy(occupancy -> {
				assertThat(occupancy.getStatus()).isEqualTo(SeatOccupancyStatus.HELD);
				assertThat(occupancy.getSectionOrder()).isBetween(0, 2);
				assertThat(occupancy.getCarType()).isEqualTo(CarType.STANDARD);
			});
		}

		@Test
		@DisplayName("이미 점유된 좌석과 구간이 겹치면 좌석 점유에 실패한다")
		void hold_fail_if_section_overlaps_existing_occupancy() {
			// given - 서울(0) -> 동대구(2) 점유 상태
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			seatOccupancyService.hold(createReservation(seoul, dongdaegu, futureExpiry()), seats);

			// when - 대전(1) -> 부산(3) 은 구간 1에서 겹침
			Reservation conflicting = createReservation(daejeon, busan, futureExpiry());

			// then
			assertThatThrownBy(() -> seatOccupancyService.hold(conflicting, seats))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_OCCUPIED);
		}

		@Test
		@DisplayName("구간이 겹치지 않으면 같은 좌석을 다시 점유할 수 있다")
		void hold_success_if_sections_do_not_overlap() {
			// given - 서울(0) -> 대전(1) 점유 상태
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			seatOccupancyService.hold(createReservation(seoul, daejeon, futureExpiry()), seats);

			// when - 대전(1) -> 부산(3) 은 인접할 뿐 겹치지 않음
			Reservation adjacent = createReservation(daejeon, busan, futureExpiry());
			seatOccupancyService.hold(adjacent, seats);

			// then
			assertThat(seatOccupancyRepository.findAllByReservationId(adjacent.getId())).hasSize(2);
		}

		@Test
		@DisplayName("만료된 점유가 남아 있어도 선삭제 후 같은 좌석을 다시 점유할 수 있다")
		void hold_success_after_expired_occupancy_is_cleaned_up() {
			// given - 이미 만료된 점유
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Reservation expired = createReservation(seoul, busan, LocalDateTime.now().minusMinutes(1));
			seatOccupancyService.hold(expired, seats);

			// when
			Reservation fresh = createReservation(seoul, busan, futureExpiry());
			seatOccupancyService.hold(fresh, seats);

			// then - 만료 행은 삭제되고 새 점유만 남는다
			assertThat(seatOccupancyRepository.findAllByReservationId(expired.getId())).isEmpty();
			assertThat(seatOccupancyRepository.findAllByReservationId(fresh.getId())).hasSize(3);
		}

		@Test
		@DisplayName("좌석 점유에 실패하면 일부 좌석도 점유되지 않는다")
		void hold_fail_leaves_no_partial_occupancy() {
			// given - 두 번째 좌석만 이미 점유된 상태
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
			seatOccupancyService.hold(createReservation(seoul, busan, futureExpiry()), List.of(seats.get(1)));

			// when - 첫 번째 좌석은 비어 있지만 두 번째 좌석에서 충돌
			Reservation conflicting = createReservation(seoul, busan, futureExpiry());
			assertThatThrownBy(() -> seatOccupancyService.hold(conflicting, seats))
				.isInstanceOf(BusinessException.class);

			// then - 첫 번째 좌석도 점유되지 않아야 한다
			assertThat(seatOccupancyRepository.findAllByReservationId(conflicting.getId())).isEmpty();
		}
	}

	@Nested
	@DisplayName("점유 확정(confirm)")
	class ConfirmTest {

		@Test
		@DisplayName("점유를 확정하면 상태가 CONFIRMED로 바뀌고 만료되지 않는다")
		void confirm_changes_status_and_clears_expiry() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
			Reservation reservation = createReservation(seoul, busan, futureExpiry());
			seatOccupancyService.hold(reservation, seats);
			Booking booking = createBooking();

			// when
			seatOccupancyService.confirm(reservation, booking, seats.size());

			// then
			List<SeatOccupancy> occupancies = seatOccupancyRepository.findAllByReservationId(reservation.getId());
			assertThat(occupancies).hasSize(6);
			assertThat(occupancies).allSatisfy(occupancy -> {
				assertThat(occupancy.getStatus()).isEqualTo(SeatOccupancyStatus.CONFIRMED);
				assertThat(occupancy.getExpiresAt()).isEqualTo(SeatOccupancy.NEVER_EXPIRES);
				assertThat(occupancy.getBooking().getId()).isEqualTo(booking.getId());
			});
		}

		@Test
		@DisplayName("점유가 이미 사라졌으면 확정에 실패한다")
		void confirm_fail_if_occupancy_already_released() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Reservation reservation = createReservation(seoul, busan, futureExpiry());
			seatOccupancyService.hold(reservation, seats);
			seatOccupancyService.releaseByReservationIds(List.of(reservation.getId()));
			Booking booking = createBooking();

			// when & then
			assertThatThrownBy(() -> seatOccupancyService.confirm(reservation, booking, seats.size()))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}

		@Test
		@DisplayName("만료된 점유는 확정되지 않는다")
		void confirm_fail_if_occupancy_expired() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Reservation reservation = createReservation(seoul, busan, LocalDateTime.now().minusMinutes(1));
			seatOccupancyService.hold(reservation, seats);
			Booking booking = createBooking();

			// when & then
			assertThatThrownBy(() -> seatOccupancyService.confirm(reservation, booking, seats.size()))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}
	}

	@Nested
	@DisplayName("점유 해제(release)")
	class ReleaseTest {

		@Test
		@DisplayName("예약을 해제하면 점유 행이 물리 삭제되어 좌석을 다시 점유할 수 있다")
		void release_by_reservation_deletes_rows_and_frees_seat() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Reservation reservation = createReservation(seoul, busan, futureExpiry());
			seatOccupancyService.hold(reservation, seats);

			// when
			seatOccupancyService.releaseByReservationIds(List.of(reservation.getId()));

			// then
			assertThat(seatOccupancyRepository.findAllByReservationId(reservation.getId())).isEmpty();

			Reservation next = createReservation(seoul, busan, futureExpiry());
			assertThatCode(() -> seatOccupancyService.hold(next, seats)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("예매를 해제하면 확정된 점유 행이 물리 삭제된다")
		void release_by_booking_deletes_confirmed_rows() {
			// given
			List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
			Reservation reservation = createReservation(seoul, busan, futureExpiry());
			seatOccupancyService.hold(reservation, seats);
			Booking booking = createBooking();
			seatOccupancyService.confirm(reservation, booking, seats.size());

			// when
			seatOccupancyService.releaseByBookingId(booking.getId());

			// then
			assertThat(seatOccupancyRepository.findAllByReservationId(reservation.getId())).isEmpty();
		}
	}

	// ===== Helper =====

	private Reservation createReservation(ScheduleStop departureStop, ScheduleStop arrivalStop, LocalDateTime expiresAt) {
		return reservationRepository.save(Reservation.create(
			"PB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
			MEMBER_NO,
			scheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			BigDecimal.valueOf(50000),
			expiresAt
		));
	}

	private Booking createBooking() {
		Member member = memberRepository.save(MemberFixture.create());
		BookingResult bookingResult = bookingTestHelper.createDefault(member, scheduleResult);
		return bookingResult.booking();
	}

	private LocalDateTime futureExpiry() {
		return LocalDateTime.now().plusMinutes(10);
	}
}
