package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper.TrainScheduleWithStopStations;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
class ReservationServiceTest {

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private ReservationRepository reservationRepository;

	private Member member;
	private Train train;
	private TrainScheduleWithStopStations schedule;
	private List<Long> standardSeatIds;

	@BeforeEach
	void setup() {
		Member member = MemberFixture.createStandardMember();
		this.member = memberRepository.save(member);
		train = trainTestHelper.createKTX();
		schedule = trainScheduleTestHelper.createSchedule(train);
		standardSeatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 2);
	}

	@Test
	@DisplayName("유효한 요청으로 예약이 성공한다")
	void validRequest_createReservation_success() {
		// given
		ReservationCreateRequest request = new ReservationCreateRequest(
			schedule.trainSchedule().getId(),
			schedule.scheduleStops().get(0).getId(),
			schedule.scheduleStops().get(1).getId(),
			List.of(new PassengerSummary(PassengerType.ADULT, 1), new PassengerSummary(PassengerType.CHILD, 1)),
			standardSeatIds,
			TripType.OW
		);

		BigDecimal totalFare = BigDecimal.valueOf(80000);

		// when
		Reservation reservation = reservationService.createReservation(request, member.getMemberDetail().getMemberNo(), totalFare);

		// then
		Reservation savedReservation = reservationRepository.findById(reservation.getId())
			.orElseThrow(() -> new AssertionError("예약이 DB에 저장되지 않았습니다"));

		assertThat(savedReservation.getMember().getId()).isEqualTo(member.getId());
		assertThat(savedReservation.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(savedReservation.getTotalPassengers()).isEqualTo(2);
		assertThat(savedReservation.getFare()).isEqualTo(80000);
		assertThat(savedReservation.getReservationCode()).isNotNull();
	}
}
