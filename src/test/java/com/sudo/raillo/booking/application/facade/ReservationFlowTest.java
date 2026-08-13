package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.SeatOccupancy;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.status.SeatOccupancyStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.booking.infrastructure.ReservationSeatRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.PaymentFacade;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 예약 생성·삭제 시 Redis Reservation과 함께 MySQL Reservation/SeatOccupancy가
 * 이중 기록(shadow write)되는지 검증한다.
 */
@ServiceTest
@DisplayName("예약 생성·확정·해제 흐름")
class ReservationFlowTest {

	@Autowired
	private ReservationFacade reservationFacade;
	@Autowired
	private PaymentFacade paymentFacade;
	@MockitoBean
	private TossPaymentClient tossPaymentClient;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private ReservationSeatRepository reservationSeatRepository;
	@Autowired
	private SeatOccupancyRepository seatOccupancyRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private TrainTestHelper trainTestHelper;
	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult scheduleResult;
	private ScheduleStop seoul;
	private ScheduleStop daejeon;
	private ScheduleStop busan;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createSmallTestTrain();

		// 서울(0) -> 대전(1) -> 부산(2)
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 20000, 40000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 30000, 60000);

		scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(6, 0), LocalTime.of(6, 5))
			.addStop("부산", LocalTime.of(8, 0), null)
			.build();

		seoul = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		daejeon = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "대전");
		busan = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
	}

	@Test
	@DisplayName("예약을 생성하면 Reservation과 좌석 x 구간 만큼의 SeatOccupancy가 함께 기록된다")
	void createReservation_also_writes_reservation_and_occupancy() {
		// given - 서울 -> 부산 (2개 구간), 좌석 2개
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();

		// when
		ReservationCreateResponse response = reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				seoul.getStation().getId(),
				busan.getStation().getId(),
				List.of(PassengerType.ADULT, PassengerType.ADULT),
				seatIds
			),
			member.getMemberDetail().getMemberNo()
		);

		// then - Reservation ID가 그대로 reservationCode가 된다
		Reservation reservation = reservationRepository.findByReservationCode(response.reservationCode())
			.orElseThrow();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
		assertThat(reservation.getMemberNo()).isEqualTo(member.getMemberDetail().getMemberNo());
		assertThat(reservation.getExpiresAt()).isAfter(java.time.LocalDateTime.now());

		assertThat(reservationSeatRepository.findAllByReservationId(reservation.getId())).hasSize(2);

		List<SeatOccupancy> occupancies = seatOccupancyRepository.findAllByReservationId(reservation.getId());
		assertThat(occupancies).hasSize(4); // 좌석 2개 x 구간 2개
		assertThat(occupancies).allSatisfy(occupancy ->
			assertThat(occupancy.getStatus()).isEqualTo(SeatOccupancyStatus.HELD));
	}

	@Test
	@DisplayName("좌석과 승객 유형이 요청한 순서대로 짝지어져 저장된다")
	void reservation_seats_keep_request_order() {
		// given - 좌석 순서를 뒤집어 요청해도 인덱스 짝이 유지되어야 한다
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		Seat firstRequested = seats.get(1);
		Seat secondRequested = seats.get(0);

		// when
		ReservationCreateResponse response = reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				seoul.getStation().getId(),
				busan.getStation().getId(),
				List.of(PassengerType.ADULT, PassengerType.CHILD),
				List.of(firstRequested.getId(), secondRequested.getId())
			),
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = reservationRepository.findByReservationCode(response.reservationCode())
			.orElseThrow();
		List<ReservationSeat> reservationSeats = reservationSeatRepository.findAllByReservationId(reservation.getId());

		assertThat(reservationSeats)
			.filteredOn(reservationSeat -> reservationSeat.getSeat().getId().equals(firstRequested.getId()))
			.singleElement()
			.satisfies(reservationSeat ->
				assertThat(reservationSeat.getPassengerType()).isEqualTo(PassengerType.ADULT));

		assertThat(reservationSeats)
			.filteredOn(reservationSeat -> reservationSeat.getSeat().getId().equals(secondRequested.getId()))
			.singleElement()
			.satisfies(reservationSeat ->
				assertThat(reservationSeat.getPassengerType()).isEqualTo(PassengerType.CHILD));
	}

	@Test
	@DisplayName("예약을 삭제하면 SeatOccupancy는 물리 삭제되고 Reservation은 해제 상태로 남는다")
	void deleteReservations_releases_reservation_and_deletes_occupancy() {
		// given
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 1);
		ReservationCreateResponse response = reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				seoul.getStation().getId(),
				busan.getStation().getId(),
				List.of(PassengerType.ADULT),
				seatIds
			),
			member.getMemberDetail().getMemberNo()
		);

		// when
		reservationFacade.deleteReservations(List.of(response.reservationCode()), member.getMemberDetail().getMemberNo());

		// then
		Reservation reservation = reservationRepository.findByReservationCode(response.reservationCode())
			.orElseThrow();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(seatOccupancyRepository.findAllByReservationId(reservation.getId())).isEmpty();
	}

	@Test
	@DisplayName("결제가 승인되면 Reservation과 SeatOccupancy가 확정 상태로 전이된다")
	void confirmPayment_transitions_reservation_and_occupancy_to_confirmed() {
		// given - 실제 예약 생성 경로를 거쳐 HELD 점유를 만든다
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 1);
		String memberNo = member.getMemberDetail().getMemberNo();
		ReservationCreateResponse created = reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				seoul.getStation().getId(),
				busan.getStation().getId(),
				List.of(PassengerType.ADULT),
				seatIds
			),
			memberNo
		);

		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(created.reservationCode())), memberNo);

		String paymentKey = "toss_pk_shadow_confirm";
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willReturn(new TossPaymentConfirmResponse(
				paymentKey, prepareResponse.orderId(), "카드", prepareResponse.amount().longValue(), "DONE"));

		// when
		paymentFacade.confirmPayment(
			new PaymentConfirmRequest(paymentKey, prepareResponse.orderId(), prepareResponse.amount()), memberNo);

		// then - 점유 행은 옮겨지지 않고 상태만 전이된다
		Reservation reservation = reservationRepository.findByReservationCode(created.reservationCode())
			.orElseThrow();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

		List<SeatOccupancy> occupancies = seatOccupancyRepository.findAllByReservationId(reservation.getId());
		assertThat(occupancies).hasSize(2); // 좌석 1개 x 구간 2개
		assertThat(occupancies).allSatisfy(occupancy -> {
			assertThat(occupancy.getStatus()).isEqualTo(SeatOccupancyStatus.CONFIRMED);
			assertThat(occupancy.getExpiresAt()).isEqualTo(SeatOccupancy.NEVER_EXPIRES);
			assertThat(occupancy.getBooking()).isNotNull();
		});
	}

	@Test
	@DisplayName("겹치지 않는 구간이면 같은 좌석으로 예약을 다시 생성할 수 있다")
	void createReservation_success_for_non_overlapping_section() {
		// given - 서울(0) -> 대전(1) 예약
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 1);
		reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				seoul.getStation().getId(),
				daejeon.getStation().getId(),
				List.of(PassengerType.ADULT),
				seatIds
			),
			member.getMemberDetail().getMemberNo()
		);

		// when - 대전(1) -> 부산(2) 은 겹치지 않는다
		ReservationCreateResponse response = reservationFacade.createReservation(
			new ReservationCreateRequest(
				scheduleResult.trainSchedule().getId(),
				daejeon.getStation().getId(),
				busan.getStation().getId(),
				List.of(PassengerType.ADULT),
				seatIds
			),
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = reservationRepository.findByReservationCode(response.reservationCode())
			.orElseThrow();
		assertThat(seatOccupancyRepository.findAllByReservationId(reservation.getId())).hasSize(1);
	}
}
