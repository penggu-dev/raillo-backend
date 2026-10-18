package com.sudo.raillo.payment.application.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static com.sudo.raillo.payment.application.PaymentAttemptIds.forApproval;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentQueryResponse;
import com.sudo.raillo.payment.adapter.persistence.PaymentOutboxJpaRepository;
import com.sudo.raillo.payment.application.PaymentApprovalFinalizer;
import com.sudo.raillo.payment.application.PaymentAttemptManager;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PaymentFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
@TestPropertySource(properties = {
	"raillo.payment.recovery.enabled=false",
	"raillo.payment.recovery.stale-after=1ms",
	"raillo.payment.recovery.initial-backoff=1m",
	"raillo.payment.recovery.max-backoff=30m"
})
class PaymentRecoveryWorkerTest {

	@Autowired
	private PaymentRecoveryWorker paymentRecoveryWorker;

	@Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private PaymentPreparer paymentPreparer;

	@Autowired
	private PaymentConfirmer paymentConfirmer;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	@Autowired
	private PaymentOutboxJpaRepository paymentOutboxRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	// 복구 Worker는 Worker 전용 빈 체인(workerApprovalReconciler → tossWorkerPaymentGateway → tossWorkerPaymentClient)을 쓴다.
	@MockitoBean(name = "tossWorkerPaymentClient")
	private TossPaymentClient tossPaymentClient;

	@MockitoSpyBean
	private SeatOccupancyRepository seatOccupancyRepository;

	@MockitoSpyBean
	private PaymentApprovalFinalizer paymentApprovalFinalizer;

	@MockitoSpyBean
	private PaymentAttemptManager paymentAttemptManager;

	private Member member;
	private String memberNo;
	private TrainScheduleResult trainScheduleResult;
	private int seatCursor;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		seatCursor = 0;
	}

	@Test
	@DisplayName("조회가 ABORTED면 시도를 FAILED로 종결하고 좌석 보호를 푼다")
	void poll_definitiveFailure_marksFailedAndReleasesSeats() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), null, f.amount().longValue(), "ABORTED"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(updated.getProcessingOwner()).isNull();
		assertThat(heldFieldTtl(f.reservation())).isBetween(1L, 600L);
	}

	@Test
	@DisplayName("조회가 아직 처리 중이면 상태를 유지하고 다음 조회 시각을 적으며 좌석을 붙잡는다")
	void poll_stillPending_backsOffAndHolds() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), null, f.amount().longValue(), "IN_PROGRESS"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
		assertThat(updated.getProcessingOwner()).isNull();
		assertThat(heldFieldTtl(f.reservation())).isEqualTo(-1L);
	}

	@Test
	@DisplayName("조회가 다른 주문의 결제로 응답하면 수동 확인으로 종결하고 좌석을 푼다")
	void poll_reviewRequired_marksReviewAndReleasesSeats() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), "SOMEONE_ELSES_ORDER", "카드", f.amount().longValue(), "DONE"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.REVIEW_REQUIRED);
		assertThat(updated.getErrorCode()).isEqualTo("REVIEW_RESULT_MISMATCH");
		assertThat(heldFieldTtl(f.reservation())).isBetween(1L, 600L);
	}

	@Test
	@DisplayName("좌석 보호 해제가 실패해도 판정 기록은 남는다")
	void poll_seatGuardFailure_keepsDecisionRecorded() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR))
			.when(seatOccupancyRepository).releaseHold(any());
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), null, f.amount().longValue(), "ABORTED"));

		// when
		paymentRecoveryWorker.poll();

		// then
		assertThat(paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
	}

	@Test
	@DisplayName("한 건의 조회가 예상 못 한 예외로 끝나도 나머지 건을 처리하고 점유를 반납한다")
	void poll_unexpectedFailureOnOneRow_continuesWithOthers() {
		// given
		RecoveryFixture broken = givenStaleInProgressAttempt();
		RecoveryFixture healthy = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(broken.paymentKey())).willThrow(new IllegalStateException("boom"));
		given(tossPaymentClient.queryPayment(healthy.paymentKey())).willReturn(new TossPaymentQueryResponse(
			healthy.paymentKey(), healthy.orderCode(), null, healthy.amount().longValue(), "ABORTED"));

		// when
		paymentRecoveryWorker.poll();

		// then
		assertThat(paymentAttemptRepository.findByAttemptId(healthy.attemptId()).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		PaymentAttempt untouched = paymentAttemptRepository.findByAttemptId(broken.attemptId()).orElseThrow();
		assertThat(untouched.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(untouched.getProcessingOwner()).isNull();
	}

	@Test
	@DisplayName("조회가 DONE이면 Toss를 다시 부르지 않고 확정해 예매와 Outbox 행을 만든다")
	void poll_confirmable_finalizesWithoutReconfirming() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), "카드", f.amount().longValue(), "DONE"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(paymentRepository.findById(f.paymentId()).orElseThrow().getPaymentStatus())
			.isEqualTo(PaymentStatus.PAID);
		assertThat(bookingRepository.findAll()).hasSize(1);
		assertThat(paymentOutboxRepository.findAll()).hasSize(1);
		verify(tossPaymentClient, never()).confirmPayment(any(PaymentConfirmCommand.class));
	}

	@Test
	@DisplayName("확정된 시도는 다음 poll에서 다시 선점되지 않는다")
	void poll_finalizedAttempt_isNotClaimedAgain() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), "카드", f.amount().longValue(), "DONE"));

		// when
		paymentRecoveryWorker.poll();
		paymentRecoveryWorker.poll();

		// then
		assertThat(bookingRepository.findAll()).hasSize(1);
	}

	@Test
	@DisplayName("확정 직전 좌석을 빼앗겼으면 수동 확인으로 남기고 예매를 만들지 않는다")
	void poll_seatTakenBeforeFinalize_marksReviewSeatLost() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		bookingTestHelper.createDefault(member, trainScheduleResult);
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), "카드", f.amount().longValue(), "DONE"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.REVIEW_REQUIRED);
		assertThat(updated.getErrorCode()).isEqualTo("REVIEW_SEAT_LOST");
		assertThat(paymentRepository.findById(f.paymentId()).orElseThrow().getPaymentStatus())
			.isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	@DisplayName("같은 주문에 이미 완료된 결제가 있어 확정이 거절되면 상태를 바꾸지 않고 다음 조회로 미룬다")
	void poll_finalizeRejectedByDuplicatePayment_keepsInProgress() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		givenAnotherPaidPaymentOnSameOrder(f);
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), "카드", f.amount().longValue(), "DONE"));

		// when
		paymentRecoveryWorker.poll();

		// then
		verify(paymentApprovalFinalizer).finalizeApproval(eq(f.paymentId()), anyLong(), any());
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
		assertThat(bookingRepository.findAll()).isEmpty();
		assertThat(paymentOutboxRepository.findAll()).isEmpty();
		assertThat(paymentRepository.findById(f.paymentId()).orElseThrow().getPaymentStatus())
			.isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	@DisplayName("Worker가 선점한 시도에 승객이 다시 요청해도 예매는 하나만 생긴다")
	void poll_concurrentUserRetry_createsSingleBooking() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), "카드", f.amount().longValue(), "DONE"));

		// when Worker가 먼저 확정하고 승객이 같은 attempt로 다시 요청한다
		paymentRecoveryWorker.poll();
		paymentConfirmer.confirm(new PaymentConfirmCommand(f.paymentKey(), f.orderCode(), f.amount()), memberNo);

		// then
		assertThat(bookingRepository.findAll()).hasSize(1);
		assertThat(paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("종결 마킹이 조용히 실패하면 다음 조회 시각을 적어 즉시 다시 선점되지 않게 한다")
	void poll_quietMarkingFailure_schedulesRetryInsteadOfImmediateReclaim() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		doThrow(new IllegalStateException("marking failed"))
			.when(paymentAttemptManager).markFailedInNewTransaction(anyLong(), anyLong(), any());
		given(tossPaymentClient.queryPayment(f.paymentKey())).willReturn(new TossPaymentQueryResponse(
			f.paymentKey(), f.orderCode(), null, f.amount().longValue(), "ABORTED"));

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(updated.getProcessingOwner()).isNull();
		assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
	}

	/** 같은 주문에 이미 PAID된 결제가 있는 상황을 만든다. */
	private void givenAnotherPaidPaymentOnSameOrder(RecoveryFixture f) {
		Order order = orderRepository.findByOrderCode(f.orderCode()).orElseThrow();
		Payment duplicate = PaymentFixture.create(member, order);
		duplicate.approve(PaymentMethod.CREDIT_CARD, "toss_pk_duplicate_" + UUID.randomUUID());
		paymentRepository.save(duplicate);
	}

	/**
	 * 예약, 주문, 결제를 만들고 승인 시도를 IN_PROGRESS로 저장한 뒤 좌석을 붙잡아 둔다. {@code stale-after=1ms}라
	 * 바로 복구 Worker의 후보가 된다.
	 */
	private RecoveryFixture givenStaleInProgressAttempt() {
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_recovery_" + UUID.randomUUID();
		String attemptId = forApproval(paymentKey);
		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult prepared = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);
		Payment payment = findPayment(prepared.orderCode());
		paymentAttemptRepository.save(PaymentAttempt.startApproval(payment.getId(), attemptId, paymentKey));
		reservationService.holdForPayment(reservation);
		return new RecoveryFixture(paymentKey, prepared.orderCode(), amount, attemptId, payment.getId(), reservation);
	}

	/** 호출마다 다음 좌석을 써 예약을 만든다. */
	private Reservation createReservationWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, seatCursor + 1);
		Long seatId = seats.get(seatCursor).getId();
		seatCursor++;

		Reservation reservation = paymentReservations.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeats(List.of(new SeatPassenger(seatId, PassengerType.ADULT)))
			.withTotalFare(fare)
			.build();

		paymentReservations.save(reservation);
		return reservation;
	}

	/** orderCode로 Payment를 찾는다. */
	private Payment findPayment(String orderCode) {
		return paymentRepository.findAll().stream()
			.filter(p -> p.getOrderCode().equals(orderCode))
			.findFirst()
			.orElseThrow();
	}

	/** 예약이 점유한 첫 좌석 field의 남은 만료 초. 만료 없음은 -1, field 없음은 -2다. */
	private long heldFieldTtl(Reservation reservation) {
		ReservationSeat seat = reservation.seats().get(0);
		String field = ReservationCacheKey.seatField(seat.seatId(), reservation.departure().stopOrder());
		var expiration = stringRedisTemplate.opsForHash()
			.getTimeToLive(ReservationCacheKey.carSeats(reservation.trainScheduleId(), seat.trainCarId()),
				TimeUnit.SECONDS, List.of(field))
			.expirationOf(field);
		if (expiration.isPersistent()) {
			return -1L;
		}
		if (expiration.isMissing()) {
			return -2L;
		}
		return expiration.value();
	}

	private record RecoveryFixture(
		String paymentKey, String orderCode, BigDecimal amount, String attemptId, Long paymentId,
		Reservation reservation) {
	}
}
