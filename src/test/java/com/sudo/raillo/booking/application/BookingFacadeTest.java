package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.BookingCreateRequest;
import com.sudo.raillo.booking.application.facade.BookingFacade;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
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
class BookingFacadeTest {

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Autowired
	private BookingFacade bookingFacade;

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
	void createBooking() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = bookingFacade.createBooking(request, memberNo);

		// then
		Booking savedBooking = bookingRepository.findById(response.bookingId()).orElseThrow();
		Member member = memberRepository.findById(savedBooking.getMember().getId()).orElseThrow();
		assertThat(member.getMemberDetail().getMemberNo()).isEqualTo(memberNo);
		assertThat(savedBooking.getBookingStatus()).isEqualTo(BookingStatus.BOOKED);
		assertThat(savedBooking.getBookingCode()).isNotNull();
	}

	@Test
	@DisplayName("예약이 성공하면 SeatBooking이 생성된다.")
	void createSeatBooking() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = bookingFacade.createBooking(request, memberNo);

		// then
		List<SeatBooking> savedSeatBookings = seatBookingRepository.findByBookingId(response.bookingId());
		assertThat(savedSeatBookings).hasSize(2);
		savedSeatBookings.forEach(seatBooking -> {
			assertThat(seatBooking.getBooking().getId()).isEqualTo(response.bookingId());
			assertThat(seatBooking.getSeat().getId()).isIn(standardSeatIds);
		});
	}

	@Test
	@DisplayName("예약이 생성될 때 좌석 정보는 오름차순으로 정렬된다.")
	void createBookingWithSortedSeats() {
		// given
		passengers = List.of(new PassengerSummary(PassengerType.ADULT, 4));
		standardSeatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 4);
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when
		var response = bookingFacade.createBooking(request, memberNo);

		// then
		assertThat(response.seatBookingIds()).containsExactlyElementsOf(standardSeatIds);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	@DisplayName("승객 수와 좌석 수가 일치하지 않으면 예외가 발생한다.")
	void shouldThrowsExceptionWhenPassengerCountMismatchesSeatCount(int count) {
		// given
		passengers = List.of(new PassengerSummary(PassengerType.ADULT, count));
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when & then
		assertThatThrownBy(() -> bookingFacade.createBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_CREATE_SEATS_INVALID.getMessage());
	}

	@Test
	@DisplayName("존재하지 않은 좌석 ID로 예약하는 경우 예외가 발생한다.")
	void shouldThrowsExceptionWhenSeatIdNotExists() {
		// given
		standardSeatIds = List.of(998L, 999L);
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);

		// when & then
		assertThatThrownBy(() -> bookingFacade.createBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("이미 선점한 좌석을 예약하는 경우 예외가 발생한다.")
	void shouldThrowsExceptionWhenSeatAlreadyBooked() {
		// given
		var request = createRequest(scheduleWithStops, departureStop, arrivalStop, passengers, standardSeatIds);
		bookingFacade.createBooking(request, memberNo);

		// when & then
		assertThatThrownBy(() -> bookingFacade.createBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_ALREADY_BOOKED.getMessage());
	}

	@Test
	@DisplayName("출발역과 도착역이 운행 스케줄 순서와 맞지 않으면 예외가 발생한다.")
	void shouldThrowsExceptionWhenDepartureAndArrivalStopsAreNotInCorrectOrder() {
		// given
		var request = createRequest(scheduleWithStops, arrivalStop, departureStop, passengers, standardSeatIds);
		trainScheduleTestHelper.createOrUpdateStationFare("부산", "서울", 50000, 10000);

		// when & then
		assertThatThrownBy(() -> bookingFacade.createBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.TRAIN_NOT_OPERATIONAL.getMessage());
	}

	private static BookingCreateRequest createRequest(
		TrainScheduleWithStopStations scheduleWithStops,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<PassengerSummary> passengers,
		List<Long> standardSeatIds
	) {
		return new BookingCreateRequest(
			scheduleWithStops.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			passengers,
			standardSeatIds,
			TripType.OW
		);
	}
}
