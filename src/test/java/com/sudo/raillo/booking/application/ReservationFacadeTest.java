package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatReservationRepository;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper.TrainScheduleWithStopStations;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class ReservationFacadeTest {

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ReservationRepository reservationRepository;

	@Autowired
	private SeatReservationRepository seatReservationRepository;

	@Autowired
	private ReservationFacade reservationFacade;

	private Train train;
	private TrainScheduleWithStopStations scheduleWithStops;
	private String memberNo;
	private List<PassengerSummary> passengers;
	private List<Long> standardSeatIds;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;

	@BeforeEach
	void setUp() {
		train = trainTestHelper.createCustomKTX(2, 2);
		scheduleWithStops = trainScheduleTestHelper.createSchedule(train);
		Member member = MemberFixture.createStandardMember();
		memberRepository.save(member);
		memberNo = member.getMemberDetail().getMemberNo();
		passengers = List.of(new PassengerSummary(PassengerType.ADULT, 2));
		standardSeatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 2);
		departureStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleWithStops, "서울");
		arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleWithStops, "부산");
	}

	@Test
	@DisplayName("예약 관련 정보를 받아 예약을 생성한다.")
	void createReservation() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = reservationFacade.createReservation(request, memberNo);

		// then
		Reservation savedReservation = reservationRepository.findById(response.reservationId()).orElseThrow();
		Member member = memberRepository.findById(savedReservation.getMember().getId()).orElseThrow();
		assertThat(member.getMemberDetail().getMemberNo()).isEqualTo(memberNo);
		assertThat(savedReservation.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(savedReservation.getReservationCode()).isNotNull();
	}

	@Test
	@DisplayName("예약이 성공하면 SeatReservation이 생성된다.")
	void createSeatReservation() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = reservationFacade.createReservation(request, memberNo);

		// then
		List<SeatReservation> savedSeatReservations = seatReservationRepository.findByReservationId(response.reservationId());
		assertThat(savedSeatReservations).hasSize(2);
		savedSeatReservations.forEach(seatReservation -> {
			assertThat(seatReservation.getReservation().getId()).isEqualTo(response.reservationId());
			assertThat(seatReservation.getSeat().getId()).isIn(standardSeatIds);
		});
	}

	@Test
	@DisplayName("예약이 생성될 때 좌석 정보는 오름차순으로 정렬된다.")
	void createReservationWithSortedSeats() {
		// given
		passengers = List.of(new PassengerSummary(PassengerType.ADULT, 4));
		standardSeatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 4);
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = reservationFacade.createReservation(request, memberNo);

		// then
		assertThat(response.seatReservationIds()).containsExactlyElementsOf(standardSeatIds);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	@DisplayName("승객 수와 좌석 수가 일치하지 않으면 예외가 발생한다.")
	void shouldThrowsExceptionWhenPassengerCountMismatchesSeatCount(int count) {
		// given
		passengers = List.of(new PassengerSummary(PassengerType.ADULT, count));
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.RESERVATION_CREATE_SEATS_INVALID.getMessage());
	}

	@Test
	@DisplayName("존재하지 않은 좌석 ID로 예약하는 경우 예외가 발생한다.")
	void shouldThrowsExceptionWhenSeatIdNotExists() {
		// given
		standardSeatIds = List.of(998L, 999L);
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("이미 선점한 좌석을 예약하는 경우 예외가 발생한다.")
	void shouldThrowsExceptionWhenSeatAlreadyReserved() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);
		reservationFacade.createReservation(request, memberNo);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_ALREADY_RESERVED.getMessage());
	}

	@Test
	@DisplayName("출발역과 도착역이 운행 스케줄 순서와 맞지 않으면 예외가 발생한다.")
	void shouldThrowsExceptionWhenDepartureAndArrivalStopsAreNotInCorrectOrder() {
		// given
		var request = createRequest(scheduleWithStops, arrivalStop, departureStop, passengers, standardSeatIds);
		trainScheduleTestHelper.createOrUpdateStationFare("부산", "서울", 50000, 10000);

		// when & then
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.TRAIN_NOT_OPERATIONAL.getMessage());
	}

	private static ReservationCreateRequest createRequest(
		TrainScheduleWithStopStations scheduleWithStops,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<PassengerSummary> passengers,
		List<Long> standardSeatIds
	) {
		return new ReservationCreateRequest(
			scheduleWithStops.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			passengers,
			standardSeatIds,
			TripType.OW
		);
	}
}
