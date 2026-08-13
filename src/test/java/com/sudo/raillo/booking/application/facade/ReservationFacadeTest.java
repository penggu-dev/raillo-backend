package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;

@ServiceTest
@DisplayName("예약 생성·삭제")
class ReservationFacadeTest {

	private static final String MEMBER_NO = "202601010001";

	@Autowired
	private ReservationFacade reservationFacade;

	@Autowired
	private ReservationRepository reservationRepository;

	@Autowired
	private SeatOccupancyRepository seatOccupancyRepository;

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private List<Seat> seats;

	@BeforeEach
	void setUp() {
		train = trainTestHelper.createCustomKTX(3, 2);
		trainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);
	}

	@Test
	@DisplayName("예약을 삭제하면 예약이 해제되고 좌석 점유가 사라진다")
	void deleteReservations_releasesOccupancy() {
		// given
		ReservationCreateResponse created = createReservation(MEMBER_NO, seats.get(0));

		// when
		reservationFacade.deleteReservations(List.of(created.reservationCode()), MEMBER_NO);

		// then
		Reservation reservation = reservationRepository.findByReservationCode(created.reservationCode()).orElseThrow();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(seatOccupancyRepository.findAllByReservationId(reservation.getId())).isEmpty();
	}

	@Test
	@DisplayName("권한이 없는 예약을 삭제하려고 시도하면 예외가 발생한다")
	void deleteReservations_fail_notOwner() {
		// given
		ReservationCreateResponse created = createReservation(MEMBER_NO, seats.get(0));

		// when & then
		assertThatThrownBy(() ->
			reservationFacade.deleteReservations(List.of(created.reservationCode()), "202601010002"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_ACCESS_DENIED);
	}

	@Test
	@DisplayName("이미 점유된 좌석을 예약하면 예외가 발생하고 예약이 남지 않는다")
	void createReservation_fail_seatAlreadyOccupied() {
		// given - 다른 사용자가 같은 구간을 점유중
		reservationTestHelper.hold(
			trainScheduleResult.trainSchedule(),
			trainScheduleResult.scheduleStops().get(0),
			trainScheduleResult.scheduleStops().get(2),
			List.of(seats.get(0))
		);
		long reservationCountBefore = reservationRepository.count();

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(
			createRequest(seats.get(0)), MEMBER_NO))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_ALREADY_OCCUPIED);

		// then - 실패한 예약은 롤백되어 남지 않는다
		assertThat(reservationRepository.count()).isEqualTo(reservationCountBefore);
	}

	@Test
	@DisplayName("출발 시간이 이미 지난 열차를 예약하면 예외가 발생한다")
	void createReservation_fail_departureTimePassed() {
		// given
		TrainScheduleResult pastSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 002 경부선")
			.train(train)
			.operationDate(LocalDate.now().minusDays(1)) // 과거 시간으로 스케줄 생성
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();

		ReservationCreateRequest request = new ReservationCreateRequest(
			pastSchedule.trainSchedule().getId(),
			pastSchedule.scheduleStops().get(0).getStation().getId(),
			pastSchedule.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, MEMBER_NO))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
	}

	@Test
	@DisplayName("출발 5분 이내 열차를 예약하면 예외가 발생한다")
	void createReservation_fail_departureWithinFiveMinutes() {
		// given
		LocalDateTime departureDateTime = LocalDateTime.now().plusMinutes(4);
		LocalDateTime arrivalDateTime = departureDateTime.plusHours(1);

		TrainScheduleResult imminentSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 005 임박")
			.train(train)
			.operationDate(departureDateTime.toLocalDate())
			.addStop("서울", null, departureDateTime.toLocalTime())
			.addStop("부산", arrivalDateTime.toLocalTime(), null)
			.build();

		ReservationCreateRequest request = new ReservationCreateRequest(
			imminentSchedule.trainSchedule().getId(),
			imminentSchedule.scheduleStops().get(0).getStation().getId(),
			imminentSchedule.scheduleStops().get(1).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, MEMBER_NO))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
	}

	@Test
	@DisplayName("자정을 넘기는 야간 열차의 출발 시간이 아직 지나지 않았으면 예약에 성공한다")
	void createReservation_success_overnightTrain() {
		// given
		TrainScheduleResult overnightSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 003 야간")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(23, 0))
			.addStop("대전", LocalTime.of(0, 30), LocalTime.of(0, 35))
			.addStop("부산", LocalTime.of(2, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 30000, 50000);

		ReservationCreateRequest request = new ReservationCreateRequest(
			overnightSchedule.trainSchedule().getId(),
			overnightSchedule.scheduleStops().get(1).getStation().getId(),  // 자정을 넘긴 중간역
			overnightSchedule.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatCode(() -> reservationFacade.createReservation(request, MEMBER_NO))
			.doesNotThrowAnyException();
	}

	// ===== Helper =====

	private ReservationCreateResponse createReservation(String memberNo, Seat seat) {
		return reservationFacade.createReservation(createRequest(seat), memberNo);
	}

	private ReservationCreateRequest createRequest(Seat seat) {
		return new ReservationCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			trainScheduleResult.scheduleStops().get(0).getStation().getId(),
			trainScheduleResult.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seat.getId())
		);
	}
}
