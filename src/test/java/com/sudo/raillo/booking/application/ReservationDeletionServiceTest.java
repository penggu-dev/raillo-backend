package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.application.service.ReservationDeletionService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Train;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
public class ReservationDeletionServiceTest {

	@Autowired
	private ReservationDeletionService reservationDeletionService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private ReservationRepository reservationRepository;

	private Member member;
	private Train train;
	private TrainScheduleTestHelper.TrainScheduleWithStopStations schedule;

	@BeforeEach
	void setup() {
		Member member = MemberFixture.createStandardMember();
		this.member = memberRepository.save(member);
		train = trainTestHelper.createKTX();
		schedule = trainScheduleTestHelper.createSchedule(train);
	}

	@Test
	@DisplayName("올바른 예약 삭제 요청 DTO로 예약 삭제에 성공한다")
	void validRequestDto_deleteReservation_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Reservation reservation = reservationTestHelper.createReservation(member, schedule);
		Reservation entity = reservationRepository.save(reservation);
		ReservationDeleteRequest request = new ReservationDeleteRequest(entity.getId());

		// when
		reservationDeletionService.deleteReservation(request);

		// then
		List<Reservation> result = reservationRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	@DisplayName("만료된 예약 일괄삭제에 성공한다")
	void expireReservations_success() {
		// given
		Reservation reservation = Reservation.builder()
			.trainSchedule(schedule.trainSchedule())
			.member(member)
			.reservationCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.reservationStatus(ReservationStatus.RESERVED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(schedule.scheduleStops().get(0))
			.arrivalStop(schedule.scheduleStops().get(1))
			.build();
		for (int i = 0; i < 3; i++) {
			reservationRepository.save(reservation);
		}

		// when
		reservationDeletionService.expireReservations();

		// then
		List<Reservation> result = reservationRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

}
