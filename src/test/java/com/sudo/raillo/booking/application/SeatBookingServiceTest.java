package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.service.SeatBookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
class SeatBookingServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SeatBookingService seatBookingService;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Booking booking;
	private Seat seat1, seat2;
	private PassengerType passengerType1, passengerType2;

	@BeforeEach
	void setup() {
		Member member = MemberFixture.createStandardMember();
		memberRepository.save(member);
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = Booking.builder()
			.trainSchedule(schedule.trainSchedule())
			.member(member)
			.reservationCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"CHILD\",\"count\":1},{\"passengerType\":\"VETERAN\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().plusMinutes(10))
			.fare(50000)
			.departureStop(schedule.scheduleStops().get(0))
			.arrivalStop(schedule.scheduleStops().get(1))
			.build();
		this.booking = bookingRepository.save(booking);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		seat1 = seats.get(0);
		seat2 = seats.get(1);
		passengerType1 = PassengerType.CHILD;
		passengerType2 = PassengerType.VETERAN;
	}

	@Test
	@DisplayName("예약, 좌석, 승객 유형으로 좌석 예약 생성에 성공한다")
	void reservationAndSeatAndPassengerType_reserveNewSeat_success() {
		// when
		SeatBooking entity = seatBookingService.reserveNewSeat(booking, seat1, passengerType1);

		// then
		assertThat(entity.getBooking().getReservationCode()).isEqualTo(booking.getReservationCode());
		assertThat(entity.getPassengerType()).isEqualTo(passengerType1);
	}

	@Test
	@DisplayName("좌석 예약 ID로 좌석 예약 삭제에 성공한다")
	void seatReservationId_deleteSeatReservation_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();

		SeatBooking seatBooking1 = SeatBooking.builder()
			.trainSchedule(trainSchedule)
			.seat(seat1)
			.booking(booking)
			.passengerType(passengerType1)
			.build();
		SeatBooking entity1 = seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.builder()
			.trainSchedule(trainSchedule)
			.seat(seat2)
			.booking(booking)
			.passengerType(passengerType2)
			.build();
		SeatBooking entity2 = seatBookingRepository.save(seatBooking2);

		// when
		seatBookingService.deleteSeatReservation(entity1.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(1);
		assertThat(result.get(0).getPassengerType()).isEqualTo(passengerType2);
	}

	@Test
	@DisplayName("예약 ID로 좌석 예약 삭제에 성공한다")
	void reservationId_deleteSeatReservation_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();

		SeatBooking seatBooking1 = SeatBooking.builder()
			.trainSchedule(trainSchedule)
			.seat(seat1)
			.booking(booking)
			.passengerType(passengerType1)
			.build();
		SeatBooking entity1 = seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.builder()
			.trainSchedule(trainSchedule)
			.seat(seat2)
			.booking(booking)
			.passengerType(passengerType2)
			.build();
		SeatBooking entity2 = seatBookingRepository.save(seatBooking2);

		// when
		seatBookingService.deleteSeatReservationByReservationId(booking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}
}
