package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.ReservationTestHelper;
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
	private ReservationTestHelper reservationTestHelper;

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

	@Test
	@DisplayName("멤버번호와 예약 ID로 특정 예약 조회에 성공한다")
	void memberNoAndReservationId_getReservation_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Reservation reservation = reservationTestHelper.createReservation(member, schedule);
		Reservation entity = reservationRepository.save(reservation);

		// when
		ReservationDetail result = reservationService.getReservation(memberNo, entity.getId());

		// then
		assertThat(result.reservationId()).isEqualTo(entity.getId());
		assertThat(result.reservationCode()).isEqualTo(reservation.getReservationCode());
		assertThat(result.departureStationName()).isEqualTo(
			schedule.scheduleStops().get(0).getStation().getStationName());
		assertThat(result.arrivalStationName()).isEqualTo(
			schedule.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 멤버번호와 잘못된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndInvalidReservationId_getReservation_throwException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Reservation reservation = reservationTestHelper.createReservation(member, schedule);
		Reservation entity = reservationRepository.save(reservation);

		// when & then
		assertThatThrownBy(() -> reservationService.getReservation(memberNo, 2L))
			.isInstanceOf(BusinessException.class);

		reservationRepository.save(reservation);
	}

	@Test
	@DisplayName("올바른 멤버번호와 만료된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndExpiredReservationId_getReservation_throwException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();
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
		Reservation entity = reservationRepository.save(reservation);

		// when & then
		assertThatThrownBy(() -> reservationService.getReservation(memberNo, entity.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.RESERVATION_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("멤버번호로 관련한 예약 목록 조회에 성공한다")
	void memberNo_getReservations_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Reservation reservation1 = reservationTestHelper.createReservation(member, scheduleBusanToDongDaegu);
		Reservation reservation2 = reservationTestHelper.createReservation(member, scheduleDaejeonToSeoul);
		Reservation entity1 = reservationRepository.save(reservation1);
		Reservation entity2 = reservationRepository.save(reservation2);

		// when
		List<ReservationDetail> result = reservationService.getReservations(memberNo);

		// then
		assertThat(result.size()).isEqualTo(2);
		ReservationDetail result1 = result.get(0);
		ReservationDetail result2 = result.get(1);

		assertThat(result1.reservationId()).isEqualTo(entity1.getId());
		assertThat(result1.reservationCode()).isEqualTo(reservation1.getReservationCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(1).getStation().getStationName());

		assertThat(result2.reservationId()).isEqualTo(entity2.getId());
		assertThat(result2.reservationCode()).isEqualTo(reservation2.getReservationCode());
		assertThat(result2.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result2.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("멤버번호로 예약 목록 조회 시 만료된 예약을 제외하고 조회에 성공한다")
	void memberNoAndExpiredReservation_getReservations_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Reservation reservation1 = Reservation.builder()
			.trainSchedule(scheduleBusanToDongDaegu.trainSchedule())
			.member(member)
			.reservationCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.reservationStatus(ReservationStatus.RESERVED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(scheduleBusanToDongDaegu.scheduleStops().get(0))
			.arrivalStop(scheduleBusanToDongDaegu.scheduleStops().get(1))
			.build();
		Reservation reservation2 = reservationTestHelper.createReservation(member, scheduleDaejeonToSeoul);
		Reservation entity1 = reservationRepository.save(reservation1);
		Reservation entity2 = reservationRepository.save(reservation2);

		// when
		List<ReservationDetail> result = reservationService.getReservations(memberNo);

		// then
		assertThat(result.size()).isEqualTo(1);
		ReservationDetail result1 = result.get(0);

		assertThat(result1.reservationId()).isEqualTo(entity2.getId());
		assertThat(result1.reservationCode()).isEqualTo(reservation2.getReservationCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
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
		reservationService.deleteReservation(request.reservationId());

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
		reservationService.expireReservations();

		// then
		List<Reservation> result = reservationRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}
}
