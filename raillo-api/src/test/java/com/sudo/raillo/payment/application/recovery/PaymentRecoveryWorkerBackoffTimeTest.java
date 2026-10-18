package com.sudo.raillo.payment.application.recovery;

import static com.sudo.raillo.payment.application.PaymentAttemptIds.forApproval;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentQueryResponse;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 조회가 최초 백오프보다 오래 걸리는 상황을 재현한다. {@link PaymentRecoveryWorkerTest}와 설정이 달라 별도 클래스로 둔다.
 */
@ServiceTest
@TestPropertySource(properties = {
	"raillo.payment.recovery.enabled=false",
	"raillo.payment.recovery.stale-after=1ms",
	"raillo.payment.recovery.initial-backoff=1s",
	"raillo.payment.recovery.max-backoff=10s"
})
class PaymentRecoveryWorkerBackoffTimeTest {

	private static final Duration SLOW_QUERY = Duration.ofSeconds(3);

	@Autowired
	private PaymentRecoveryWorker paymentRecoveryWorker;

	@Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private PaymentPreparer paymentPreparer;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private ReservationService reservationService;

	// 복구 Worker는 Worker 전용 빈 체인(workerApprovalReconciler → tossWorkerPaymentGateway → tossWorkerPaymentClient)을 쓴다.
	@MockitoBean(name = "tossWorkerPaymentClient")
	private TossPaymentClient tossPaymentClient;

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
	@DisplayName("조회가 최초 백오프보다 오래 걸려도 다음 조회 시각은 조회가 끝난 시점 뒤로 찍힌다")
	void poll_slowQuery_schedulesRetryFromQueryCompletionTime() {
		// given
		RecoveryFixture f = givenStaleInProgressAttempt();
		given(tossPaymentClient.queryPayment(f.paymentKey())).willAnswer(invocation -> {
			Thread.sleep(SLOW_QUERY.toMillis());
			return new TossPaymentQueryResponse(
				f.paymentKey(), f.orderCode(), null, f.amount().longValue(), "IN_PROGRESS");
		});
		LocalDateTime pollStartedAt = LocalDateTime.now();

		// when
		paymentRecoveryWorker.poll();

		// then
		PaymentAttempt updated = paymentAttemptRepository.findByAttemptId(f.attemptId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(updated.getNextRetryAt()).isAfter(pollStartedAt.plus(SLOW_QUERY));
	}

	/** 예약, 주문, 결제를 만들고 승인 시도를 IN_PROGRESS로 저장한 뒤 좌석을 붙잡아 둔다. */
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
		return new RecoveryFixture(paymentKey, prepared.orderCode(), amount, attemptId);
	}

	/** 첫 좌석으로 예약을 만든다. */
	private Reservation createReservationWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 1);

		Reservation reservation = paymentReservations.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeats(List.of(new SeatPassenger(seats.get(0).getId(), PassengerType.ADULT)))
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

	private record RecoveryFixture(String paymentKey, String orderCode, BigDecimal amount, String attemptId) {
	}
}
