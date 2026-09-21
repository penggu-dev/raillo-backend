package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static com.sudo.raillo.payment.application.PaymentAttemptIds.forApproval;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentConfirmResponse;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class PaymentConfirmServiceTest {
	@org.springframework.beans.factory.annotation.Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private PaymentConfirmer paymentConfirmer;

	@Autowired
	private PaymentPreparer paymentPreparer;

	@MockitoBean
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	@Autowired
	private PaymentReservationTestHelper bookingRedisRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;



	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private com.sudo.raillo.payment.application.outbox.PaymentOutboxWorker paymentOutboxWorker;

	@MockitoSpyBean
	private PaymentOutboxRepository paymentOutboxRepository;

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

	/** 결제 승인 시작 TX A에서 저장한 paymentKey가 승인 확정 TX B 이후에도 유지되는지 검증한다. */
	@Test
	@DisplayName("결제 승인 성공 시 paymentKey가 DB에 정상 저장된다")
	void confirmPayment_paymentKeyPersistedInDatabase() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_test_12345";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		PaymentConfirmResult confirmedResult = paymentConfirmer.confirm(confirmRequest, memberNo);

		// then
		Payment savedPayment = paymentRepository.findById(confirmedResult.paymentId()).orElseThrow();
		assertThat(savedPayment.getPaymentKey())
			.as("TX A에서 저장한 paymentKey는 TX B 확정 후에도 유지되어야 한다")
			.isEqualTo(paymentKey);
	}

	@Test
	@DisplayName("결제 승인 성공 시 Payment 상태가 PAID로 변경된다")
	void confirmPayment_paymentStatusChangedToPaid() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_test_67890";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		PaymentConfirmResult confirmedResult = paymentConfirmer.confirm(confirmRequest, memberNo);

		// then
		Payment savedPayment = paymentRepository.findById(confirmedResult.paymentId()).orElseThrow();
		assertThat(savedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getPaidAt()).isNotNull();

		Order savedOrder = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.ORDERED);
	}

	@Test
	@DisplayName("Toss 승인 API는 DB 트랜잭션이 없는 상태에서 호출된다")
	void confirmPayment_callsGatewayWithoutDatabaseTransaction() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_without_transaction";
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willAnswer(invocation -> {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive())
					.as("외부 Toss 호출 중에는 DB 트랜잭션이 열려 있으면 안 된다")
					.isFalse();
				return new TossPaymentConfirmResponse(
					paymentKey, prepared.orderCode(), "카드", amount.longValue(), "DONE");
			});

		PaymentConfirmCommand command = new PaymentConfirmCommand(
			paymentKey, prepared.orderCode(), amount);

		// when
		PaymentConfirmResult result = paymentConfirmer.confirm(command, memberNo);

		// then
		assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PAID);
		verify(tossPaymentClient).confirmPayment(any(PaymentConfirmCommand.class));
	}

	/**
	 * REQUIRES_NEW 독립 커밋 검증
	 *
	 * <p>토스 결제 실패가 발생해도 승인 시도 시작 트랜잭션에서 저장한
	 * paymentKey와 실패 정보는 DB에 남아있어야 합니다.</p>
	 */
	@Test
	@DisplayName("토스 결제 확정 실패 시 사전에 저장한 paymentKey와 실패 상태가 유지된다")
	void confirmPayment_tossFailure_paymentKeySurvivedByRequiresNew() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_requires_new_test";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// 토스 API가 확정적인 4xx 실패를 반환
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(400, "INVALID_REQUEST", "test error"));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(TossPaymentException.class)
			.hasFieldOrPropertyWithValue("httpStatus", 400)
			.hasFieldOrPropertyWithValue("errorCode", "INVALID_REQUEST")
			.hasMessageContaining("test error");

		// then: 옵션 Y — Payment는 PENDING 유지, PaymentAttempt만 FAILED로 마킹
		Order savedOrder = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		Payment savedPayment = paymentRepository.findByOrder(savedOrder).orElseThrow();
		assertThat(savedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(savedPayment.getPaymentKey())
			.as("옵션 3 — Payment.paymentKey는 승인 확정 시에만 저장. 실패에서는 null 유지")
			.isNull();

		PaymentAttempt attempt = paymentAttemptRepository
			.findByAttemptId(forApproval(paymentKey)).orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getPaymentKey()).isEqualTo(paymentKey);
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	@DisplayName("Toss 5xx 응답은 결과 불명으로 처리하여 Payment와 Attempt를 진행 중 상태로 유지한다")
	void confirmPayment_tossServerError_keepsAttemptInProgress() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_unknown_result";
		String attemptId = forApproval(paymentKey);
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(500, "INTERNAL_SERVER_ERROR", "응답 결과 불명"));
		PaymentConfirmCommand command = new PaymentConfirmCommand(
			paymentKey, prepared.orderCode(), amount);

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(command, memberNo))
			.isInstanceOf(TossPaymentException.class)
			.hasFieldOrPropertyWithValue("httpStatus", 500);

		// then
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(attempt.getErrorCode()).isNull();

		Order savedOrder = orderRepository.findByOrderCode(prepared.orderCode()).orElseThrow();
		Payment savedPayment = paymentRepository.findByOrder(savedOrder).orElseThrow();
		assertThat(savedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(savedPayment.getPaymentKey()).isNull();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	@Test
	@DisplayName("SUCCEEDED attempt로 재요청하면 이전 결과를 반환하고 Toss는 호출되지 않는다")
	void confirmPayment_retryOnSucceededAttempt_returnsPreviousResult() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_retry_success";
		String attemptId = forApproval(paymentKey);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class))).willReturn(tossResponse);

		PaymentConfirmCommand request = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// 첫 요청 (성공)
		PaymentConfirmResult first = paymentConfirmer.confirm(request, memberNo);

		// when: 같은 attemptId로 재요청
		PaymentConfirmResult second = paymentConfirmer.confirm(request, memberNo);

		// then: 동일한 paymentId 반환
		assertThat(second.paymentId()).isEqualTo(first.paymentId());
		// Toss는 처음 한 번만 호출
		verify(tossPaymentClient, times(1)).confirmPayment(any(PaymentConfirmCommand.class));
	}

	@Test
	@DisplayName("FAILED attempt로 재요청하면 PAYMENT_ATTEMPT_ALREADY_FAILED 예외를 던진다")
	void confirmPayment_retryOnFailedAttempt_throwsAlreadyFailed() {
		// given: 첫 요청은 Toss 실패
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_retry_failed";
		String attemptId = forApproval(paymentKey);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(400, "REJECT_CARD_PAYMENT", "카드 승인 거절"));

		PaymentConfirmCommand request = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		assertThatThrownBy(() -> paymentConfirmer.confirm(request, memberNo))
			.isInstanceOf(TossPaymentException.class);

		// when + then: 같은 attemptId로 재요청 → BusinessException
		assertThatThrownBy(() -> paymentConfirmer.confirm(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining("이미 실패한 결제 시도");
	}

	@Test
	@DisplayName("IN_PROGRESS attempt가 존재하는 상태에서 재요청하면 PAYMENT_ATTEMPT_IN_PROGRESS 예외를 던진다")
	void confirmPayment_retryOnInProgressAttempt_throwsInProgress() {
		// given: Payment가 있고, IN_PROGRESS attempt를 직접 저장
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_in_progress";
		String attemptId = forApproval(paymentKey);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);
		Payment payment = paymentRepository.findAll().stream()
			.filter(p -> p.getOrderCode().equals(preparedResult.orderCode()))
			.findFirst()
			.orElseThrow();

		paymentAttemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), attemptId, paymentKey));

		PaymentConfirmCommand request = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when + then
		assertThatThrownBy(() -> paymentConfirmer.confirm(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining("결제 처리 중");

		// Toss는 호출되지 않음
		verify(tossPaymentClient, never()).confirmPayment(any(PaymentConfirmCommand.class));
	}

	@Test
	@DisplayName("결제 승인 성공 시 attempt는 SUCCEEDED, payment_outbox에 BOOKING_CONFIRMED 행이 저장된다")
	void confirmPayment_whenSucceeded_marksAttemptSucceededAndInsertsOutbox() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_success_outbox";
		String attemptId = forApproval(paymentKey);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		PaymentConfirmResult confirmedResult = paymentConfirmer.confirm(confirmRequest, memberNo);

		// then - attempt SUCCEEDED
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);

		// then - outbox에 BOOKING_CONFIRMED PENDING 행 존재
		String dedupKey = "payment:%d:booking-confirmed".formatted(confirmedResult.paymentId());
		PaymentOutbox outbox = paymentOutboxRepository.findByDeduplicationKey(dedupKey).orElseThrow();
		assertThat(outbox.getType()).isEqualTo(PaymentOutboxType.BOOKING_CONFIRMED);
		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(outbox.getAggregateId()).isEqualTo(confirmedResult.paymentId());
		assertThat(outbox.getPayload()).contains(reservation.reservationId());
	}

	@Test
	@DisplayName("승인 확정 중 Outbox 저장이 실패하면 DB 변경 전체가 롤백되고 Attempt는 IN_PROGRESS로 유지된다")
	void confirmPayment_finalizationFailure_rollsBackDatabaseChanges() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_finalization_rollback";
		String attemptId = forApproval(paymentKey);
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(new TossPaymentConfirmResponse(
				paymentKey, prepared.orderCode(), "카드", amount.longValue(), "DONE"));
		doThrow(new IllegalStateException("outbox 저장 실패"))
			.when(paymentOutboxRepository).save(any(PaymentOutbox.class));

		PaymentConfirmCommand command = new PaymentConfirmCommand(
			paymentKey, prepared.orderCode(), amount);

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(command, memberNo))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("outbox 저장 실패");

		// then
		Order savedOrder = orderRepository.findByOrderCode(prepared.orderCode()).orElseThrow();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

		Payment savedPayment = paymentRepository.findByOrder(
			orderRepository.findByOrderCode(prepared.orderCode()).orElseThrow()).orElseThrow();
		assertThat(savedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(savedPayment.getPaidAt()).isNull();
		assertThat(savedPayment.getPaymentKey()).isNull();

		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(bookingRepository.count()).isZero();
		assertThat(paymentOutboxRepository.findByDeduplicationKey(
			"payment:%d:booking-confirmed".formatted(savedPayment.getId()))).isEmpty();
		assertThat(bookingRedisRepository.find(reservation)).isPresent();
	}

	@Test
	@DisplayName("Toss 승인 실패 시 PaymentAttempt가 FAILED로 기록된다")
	void confirmPayment_whenTossFails_marksAttemptFailed() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_attempt_fail";
		String attemptId = forApproval(paymentKey);

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(400, "REJECT_CARD_PAYMENT", "카드 승인 거절"));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(TossPaymentException.class);

		// then - attempt는 REQUIRES_NEW로 저장되므로 outer 롤백과 무관하게 FAILED로 남는다
		PaymentAttempt attempt = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow();
		assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(attempt.getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
		assertThat(attempt.getErrorMessage()).isEqualTo("카드 승인 거절");
		assertThat(attempt.getAttemptType()).isEqualTo(PaymentAttemptType.APPROVAL);
		assertThat(attempt.getPaymentKey()).isEqualTo(paymentKey);
	}

	@Test
	@DisplayName("결제 승인 후 worker가 실행되어도 R 점유를 유지한다")
	void confirmPayment_reservation_protected_after_worker_tick() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_hold_release_test";

		Reservation reservation = createReservationWithHold(amount);
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);
		Long seatId = reservation.seats().get(0).seatId();

		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when
		paymentConfirmer.confirm(confirmRequest, memberNo);
		paymentOutboxWorker.poll();

		// then - R→B 처리기는 후속 PR에서 붙이며 그 전에는 다른 예약을 허용하지 않는다.
		Reservation another = paymentReservations.builder()
			.withMemberNo(memberNo).withTrainScheduleId(reservation.trainScheduleId())
			.withDepartureStopId(departureStop.getId()).withArrivalStopId(arrivalStop.getId())
			.withSeats(List.of(new SeatPassenger(seatId, PassengerType.ADULT))).build();
		assertThatThrownBy(() -> bookingRedisRepository.save(another)).isInstanceOf(BusinessException.class);

	}

	// ========== 실패 시나리오 테스트 ==========

	@Test
	@DisplayName("Reservation이 TTL 만료되면 RESERVATION_EXPIRED 예외가 발생한다")
	void confirmPayment_reservationExpired_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_expired_test";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// Reservation을 Redis에서 삭제하여 TTL 만료 시뮬레이션
		bookingRedisRepository.delete(reservation);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED)
			.hasMessage(BookingError.RESERVATION_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("Order 소유자가 아닌 회원이 결제 승인을 시도하면 ORDER_ACCESS_DENIED 예외가 발생한다")
	void confirmPayment_orderOwnerMismatch_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_owner_test";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// 다른 회원 생성
		Member otherMember = memberRepository.save(MemberFixture.createOther());
		String otherMemberNo = otherMember.getMemberDetail().getMemberNo();


		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, otherMemberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", OrderError.ORDER_ACCESS_DENIED)
			.hasMessage(OrderError.ORDER_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("요청 금액과 Order 금액이 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
	void confirmPayment_amountMismatch_throwsException() {
		// given
		BigDecimal orderAmount = BigDecimal.valueOf(50000);
		BigDecimal wrongRequestAmount = BigDecimal.valueOf(30000);
		String paymentKey = "toss_pk_amount_test";

		Reservation reservation = createReservationWithHold(orderAmount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), wrongRequestAmount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH)
			.hasMessage(PaymentError.PAYMENT_AMOUNT_MISMATCH.getMessage());
	}

	@Test
	@DisplayName("이미 PAID 상태의 결제가 존재하면 PAYMENT_ALREADY_COMPLETED 예외가 발생한다")
	void confirmPayment_duplicatePayment_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_duplicate_test";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		// 기존 Payment를 PAID 상태로 변경
		Order order = orderRepository.findByOrderCode(preparedResult.orderCode()).orElseThrow();
		Payment existingPayment = paymentRepository.findByOrder(order).orElseThrow();
		existingPayment.approve(PaymentMethod.CREDIT_CARD, "test-payment-key");
		paymentRepository.saveAndFlush(existingPayment);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		// when & then
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ALREADY_COMPLETED)
			.hasMessage(PaymentError.PAYMENT_ALREADY_COMPLETED.getMessage());
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

		// 실제 플로우처럼 Seat Hold 먼저 설정 (ReservationFacade가 하는 일)

		bookingRedisRepository.save(reservation);
		return reservation;
	}

	@ParameterizedTest
	@EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "REFUNDED"})
	@DisplayName("승인 불가능한 결제(CANCELLED · REFUNDED)에는 재요청해도 토스 호출 전에 거절한다")
	void rejects_new_attempt_for_unpayable_payment_before_gateway(PaymentStatus status) {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "new-key";
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);
		Order order = orderRepository.findByOrderCode(prepared.orderCode()).orElseThrow();
		Payment payment = paymentRepository.findByOrder(order).orElseThrow();
		String previousKey = null;
		switch (status) {
			case CANCELLED -> payment.cancel("취소");
			case REFUNDED -> {
				previousKey = "previous-key";
				payment.approve(PaymentMethod.CREDIT_CARD, previousKey);
				payment.refund();
			}
			default -> throw new AssertionError(status);
		}
		paymentRepository.saveAndFlush(payment);
		given(tossPaymentClient.confirmPayment(any())).willReturn(new TossPaymentConfirmResponse(
			paymentKey, prepared.orderCode(), "카드", amount.longValue(), "DONE"));
		PaymentConfirmCommand command = new PaymentConfirmCommand(paymentKey, prepared.orderCode(), amount);

		// when / then
		assertThatThrownBy(() -> paymentConfirmer.confirm(command, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_APPROVABLE);
		verify(tossPaymentClient, never()).confirmPayment(any());
		assertThat(paymentAttemptRepository.findByAttemptId(forApproval(paymentKey))).isEmpty();
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isEqualTo(previousKey);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("승인 진행 중 재요청은 paymentKey가 같거나 달라도 토스를 다시 호출하지 않는다")
	void concurrent_confirm_calls_gateway_once(boolean samePaymentKey) throws Exception {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);
		PaymentConfirmCommand firstCommand = new PaymentConfirmCommand("first-key", prepared.orderCode(), amount);
		PaymentConfirmCommand secondCommand = new PaymentConfirmCommand(
			samePaymentKey ? "first-key" : "second-key", prepared.orderCode(), amount);
		CountDownLatch gatewayEntered = new CountDownLatch(1);
		CountDownLatch releaseGateway = new CountDownLatch(1);
		given(tossPaymentClient.confirmPayment(any())).willAnswer(invocation -> {
			gatewayEntered.countDown();
			if (!releaseGateway.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("승인 응답 대기 시간 초과");
			}
			return new TossPaymentConfirmResponse("first-key", prepared.orderCode(), "카드", amount.longValue(), "DONE");
		});

		try (var executor = Executors.newSingleThreadExecutor()) {
			var first = executor.submit(() -> paymentConfirmer.confirm(firstCommand, memberNo));
			try {
				assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();

				// when / then: 첫 승인의 외부 응답을 기다리는 동안 두 번째 요청을 실행한다.
				assertThatThrownBy(() -> paymentConfirmer.confirm(secondCommand, memberNo))
					.isInstanceOf(BusinessException.class)
					.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
			} finally {
				releaseGateway.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS).paymentStatus()).isEqualTo(PaymentStatus.PAID);
		}
		verify(tossPaymentClient, times(1)).confirmPayment(any());
	}

	// Payment.paymentKey는 승인 확정 시(TX B의 approve) 저장되므로, 다른 Payment가 이미 사용 중인
	// paymentKey로 인한 unique 무결성 오류는 TX A가 아니라 Toss 응답을 받은 뒤 TX B에서 발생한다.
	// 이 시나리오의 흐름 변화가 커서 옛 테스트는 삭제하고, 새 테스트는 통합 테스트 재작성(#266 후속)에서 다룬다.

	// attemptId 필드는 API에서 제거되어 서버가 paymentKey에서 SHA-256으로 파생한다.
	// 64자 초과 검증 테스트는 필드 삭제로 무효화되어 제거.
}
