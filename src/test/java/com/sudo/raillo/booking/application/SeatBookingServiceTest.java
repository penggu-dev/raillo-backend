package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.application.service.SeatBookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
class SeatBookingServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private SeatBookingService seatBookingService;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Test
	@DisplayName("예약, 좌석, 승객 유형으로 좌석 예약 생성에 성공한다")
	void bookingAndSeatAndPassengerType_reserveNewSeat_success() {
		// given
		Member member = MemberFixture.createStandardMember();
		memberRepository.save(member);
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

		// when
		SeatBooking entity = seatBookingService.reserveNewSeat(booking, seat, PassengerType.CHILD);

		// then
		assertThat(entity.getBooking().getBookingCode()).isEqualTo(booking.getBookingCode());
		assertThat(entity.getPassengerType()).isEqualTo(PassengerType.CHILD);
	}

	@Test
	@DisplayName("좌석 예약 ID로 좌석 예약 삭제에 성공한다")
	void seatBookingId_deleteSeatBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();
		Member member = MemberFixture.createStandardMember();
		memberRepository.save(member);
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		SeatBooking seatBooking1 = SeatBooking.create(
			trainSchedule,
			seats.get(0),
			booking,
			PassengerType.CHILD
		);
		SeatBooking savedSeatBooking = seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.create(
			trainSchedule,
			seats.get(1),
			booking,
			PassengerType.VETERAN
		);
		seatBookingRepository.save(seatBooking2);

		// when
		seatBookingService.deleteSeatBooking(savedSeatBooking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(1);
		assertThat(result.get(0).getPassengerType()).isEqualTo(PassengerType.VETERAN);
	}

	@Test
	@DisplayName("예약 ID로 좌석 예약 삭제에 성공한다")
	void bookingId_deleteSeatBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();
		Member member = MemberFixture.createStandardMember();
		memberRepository.save(member);
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		SeatBooking seatBooking1 = SeatBooking.create(
			trainSchedule,
			seats.get(0),
			booking,
			PassengerType.CHILD
		);
		seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.create(
			trainSchedule,
			seats.get(1),
			booking,
			PassengerType.VETERAN
		);
		seatBookingRepository.save(seatBooking2);

		// when
		seatBookingService.deleteSeatBookingByBookingId(booking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}
}
