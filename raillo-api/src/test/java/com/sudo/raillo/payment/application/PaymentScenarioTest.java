package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentConfirmResponse;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
@DisplayName("결제 시나리오 테스트 (준비 → 승인 전체 흐름)")
class PaymentScenarioTest {
	@org.springframework.beans.factory.annotation.Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private PaymentPreparer paymentPreparer;

	@Autowired
	private PaymentConfirmer paymentConfirmer;

	@MockitoBean
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	@Autowired
	private com.sudo.raillo.payment.application.required.PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Autowired
	private PaymentReservationTestHelper bookingRedisRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;


	private Member member;
	private String memberNo;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("결제 준비 → 토스 승인 성공 시 Order ORDERED, Payment PAID, Booking 생성, 좌석 확정")
	void fullFlow_prepareAndConfirmSuccess() {
		// given - 결제 준비
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_scenario_success";
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// 준비 단계 검증
		assertThat(preparedResult.orderCode()).isNotNull();
		assertThat(preparedResult.totalAmount()).isEqualByComparingTo(amount);

		// given - 토스 승인 성공 Mock
		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when - 결제 승인
		PaymentConfirmResult confirmedResult = paymentConfirmer.confirm(confirmRequest, memberNo);

		// then - Payment 상태 검증
		Payment payment = paymentRepository.findById(confirmedResult.paymentId()).orElseThrow();
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(payment.getPaymentKey()).isEqualTo(paymentKey);
		assertThat(payment.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
		assertThat(payment.getPaidAt()).isNotNull();

		// then - Order 상태 검증
		Order order = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ORDERED);

		// then - Booking 생성 검증
		assertThat(bookingRepository.findAll()).isNotEmpty();

		// then - SeatBooking 생성 검증
		List<Long> seatIds = reservation.getSeatIds();
		List<SeatBooking> seatBookings = seatBookingRepository.findOverlappingSeatBookings(
			trainScheduleResult.trainSchedule().getId(),
			seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);
		assertThat(seatBookings).hasSize(1);

		// Reservation 삭제와 Seat Hold 해제는 PaymentOutboxWorker가 비동기로 수행하므로
		// PaymentConfirmServiceTest.confirmPayment_holdReleasedAfterWorkerTick에서 Worker tick 후 검증한다.
	}

	@Test
	@DisplayName("결제 준비 → 토스 승인 실패(4xx) 시 Payment PENDING 유지, PaymentAttempt만 FAILED")
	void fullFlow_prepareAndTossFailure() {
		// given - 결제 준비
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_scenario_fail";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// given - 토스 승인 실패 Mock (4xx 에러)
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(400, "REJECT_CARD_PAYMENT", "카드 결제가 거절되었습니다."));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then - 예외 발생
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(TossPaymentException.class)
			.hasFieldOrPropertyWithValue("httpStatus", 400)
			.hasFieldOrPropertyWithValue("errorCode", "REJECT_CARD_PAYMENT")
			.hasMessageContaining("카드 결제가 거절되었습니다.");

		// then - 옵션 Y: Payment는 PENDING 유지, paymentKey는 승인 확정 시에만 저장되므로 null
		Order order = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		Payment payment = paymentRepository.findByOrder(order).orElseThrow();
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getPaymentKey()).isNull();

		// then - PaymentAttempt만 FAILED로 마킹되고 오류 정보가 기록됨
		com.sudo.raillo.payment.domain.PaymentAttempt attempt = paymentAttemptRepository
			.findByAttemptId(com.sudo.raillo.payment.application.PaymentAttemptIds.forApproval(paymentKey))
			.orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(com.sudo.raillo.payment.domain.PaymentAttemptStatus.FAILED);
		assertThat(attempt.getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
		assertThat(attempt.getErrorMessage()).isEqualTo("카드 결제가 거절되었습니다.");

		// then - Order는 PENDING 유지 (TX B가 실행되지 않음)
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

		// then - Booking은 생성되지 않음
		assertThat(bookingRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("결제 준비 → 토스 응답 금액 불일치 시 PAYMENT_AMOUNT_MISMATCH 예외 발생")
	void fullFlow_tossResponseAmountMismatch() {
		// given - 결제 준비
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_scenario_amount_mismatch";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// given - 토스 응답 금액 불일치 Mock (요청은 50000인데 토스가 60000 응답)
		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", 60000L, "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH)
			.hasMessageContaining("게이트웨이 결제 금액이 요청 금액과 일치하지 않습니다");
	}

	@Test
	@DisplayName("결제 준비 → 알 수 없는 결제수단 응답 시 INVALID_PAYMENT_METHOD 예외 발생")
	void fullFlow_unknownPaymentMethod() {
		// given - 결제 준비
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_scenario_unknown_method";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// given - 알 수 없는 결제수단 Mock
		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "비트코인", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.INVALID_PAYMENT_METHOD)
			.hasMessageContaining("지원하지 않는 결제 수단입니다");
	}

	@Test
	@DisplayName("복수 Reservation 결제 준비 → 토스 승인 성공 시 Booking이 건수만큼 생성된다")
	void fullFlow_multipleReservations_success() {
		// given - 좌석 2개로 Reservation 2건 생성
		BigDecimal farePerBooking = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_scenario_multi";

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);
		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 2);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();

		Reservation pb1 = createSingleSeatReservation(
			departureStop, arrivalStop, seatIds.get(0), farePerBooking);
		Reservation pb2 = createSingleSeatReservation(
			departureStop, arrivalStop, seatIds.get(1), farePerBooking);

		Long trainCarId = seats.get(0).getTrainCar().getId();

		// Seat Hold

		bookingRedisRepository.save(pb1);
		bookingRedisRepository.save(pb2);

		// given - 결제 준비 (2건 묶음)
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(pb1.reservationId(), pb2.reservationId())), memberNo);

		BigDecimal totalAmount = preparedResult.totalAmount();

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", totalAmount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), totalAmount);

		// when
		paymentConfirmer.confirm(confirmRequest, memberNo);

		// then - Booking 2건 생성 검증
		assertThat(bookingRepository.findAll()).hasSize(2);

		// then - SeatBooking 2건 생성 검증
		List<SeatBooking> seatBookings = seatBookingRepository.findOverlappingSeatBookings(
			trainScheduleResult.trainSchedule().getId(),
			seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);
		assertThat(seatBookings).hasSize(2);

		// Reservation 삭제는 PaymentOutboxWorker가 비동기로 수행하므로 여기서는 검증하지 않는다.

		// then - Order, Payment 상태 검증
		Order order = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ORDERED);

		Payment payment = paymentRepository.findByOrder(order).orElseThrow();
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
	}

	private Reservation createSingleSeatReservation(
		ScheduleStop departureStop, ScheduleStop arrivalStop, Long seatId, BigDecimal fare) {
		return paymentReservations.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeats(List.of(new SeatPassenger(seatId, PassengerType.ADULT)))
			.withTotalFare(fare)
			.build();
	}

	private Reservation createReservationWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 1);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		Long trainCarId = seats.get(0).getTrainCar().getId();

		Reservation reservation = paymentReservations.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeats(List.of(
				new SeatPassenger(seatIds.get(0), PassengerType.ADULT)
			))
			.withTotalFare(fare)
			.build();


		bookingRedisRepository.save(reservation);
		return reservation;
	}
}
