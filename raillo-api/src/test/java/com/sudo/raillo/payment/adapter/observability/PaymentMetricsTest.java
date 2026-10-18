package com.sudo.raillo.payment.adapter.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

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
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.RecoveryMetrics;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
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
import com.sudo.raillo.train.domain.type.CarType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@ServiceTest
class PaymentMetricsTest {
	@org.springframework.beans.factory.annotation.Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private PaymentPreparer paymentPreparer;

	@Autowired
	private PaymentConfirmer paymentConfirmer;

	@MockitoBean(name = "tossPaymentClient")
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PaymentReservationTestHelper bookingRedisRepository;


	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private RecoveryMetrics recoveryMetrics;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	private Member member;
	private String memberNo;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();

		var train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("결제 준비 성공 시 payment_prepare_total 카운터가 증가한다")
	void preparePayment_incrementsPrepareMetric() {
		// given
		Reservation reservation = createReservationWithHold(BigDecimal.valueOf(50000));
		PaymentPrepareCommand request = new PaymentPrepareCommand(List.of(reservation.reservationId()));

		double before = meterRegistry.counter("payment_prepare_total").count();

		// when
		paymentPreparer.prepare(request, memberNo);

		// then
		double after = meterRegistry.counter("payment_prepare_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("결제 승인 성공 시 payment_confirm_success_total 카운터가 증가한다")
	void confirmPayment_success_incrementsConfirmSuccessMetric() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_metrics_success";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, preparedResult.orderCode(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willReturn(tossResponse);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		double before = meterRegistry.counter("payment_confirm_success_total").count();

		// when
		paymentConfirmer.confirm(confirmRequest, memberNo);

		// then
		double after = meterRegistry.counter("payment_confirm_success_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("토스 결제 실패 시 payment_confirm_failure_total{reason=toss_error, error_code=INVALID_REQUEST} 카운터가 증가한다")
	void confirmPayment_tossFailure_incrementsConfirmFailureTossError() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_metrics_toss_fail";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new TossPaymentException(400, "INVALID_REQUEST", "test error"));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "toss_error", "http_status", "400", "error_code", "INVALID_REQUEST").count();

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(TossPaymentException.class);

		// then
		double after = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "toss_error", "http_status", "400", "error_code", "INVALID_REQUEST").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("금액 불일치로 결제 실패 시 payment_confirm_failure_total{reason=validation_error, error_code=PAYMENT_201} 카운터가 증가한다")
	void confirmPayment_amountMismatch_incrementsConfirmFailureValidationError() {
		// given
		BigDecimal orderAmount = BigDecimal.valueOf(50000);
		BigDecimal wrongAmount = BigDecimal.valueOf(30000);
		String paymentKey = "toss_pk_metrics_validation_fail";

		Reservation reservation = createReservationWithHold(orderAmount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), wrongAmount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "validation_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_AMOUNT_MISMATCH.getStatus().value()),
			"error_code", PaymentError.PAYMENT_AMOUNT_MISMATCH.getCode()).count();

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH);

		// then
		double after = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "validation_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_AMOUNT_MISMATCH.getStatus().value()),
			"error_code", PaymentError.PAYMENT_AMOUNT_MISMATCH.getCode()).count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("예상치 못한 Exception 발생 시 payment_confirm_failure_total{reason=system_error, error_code=UNKNOWN} 카운터가 증가한다")
	void confirmPayment_unexpectedException_incrementsConfirmFailureSystemError() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_metrics_unexpected";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new RuntimeException("unexpected error"));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error", "http_status", "500", "error_code", "UNKNOWN").count();

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(RuntimeException.class);

		// then
		double after = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error", "http_status", "500", "error_code", "UNKNOWN").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("5xx BusinessException 발생 시 payment_confirm_failure_total{reason=system_error, error_code=PAYMENT_901} 카운터가 증가한다")
	void confirmPayment_5xxBusinessException_incrementsConfirmFailureSystemError() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_metrics_system_error";

		Reservation reservation = createReservationWithHold(amount);
		PaymentPrepareResult preparedResult = paymentPreparer.prepare(
			new PaymentPrepareCommand(List.of(reservation.reservationId())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmCommand.class)))
			.willThrow(new BusinessException(PaymentError.PAYMENT_SYSTEM_ERROR));

		PaymentConfirmCommand confirmRequest = new PaymentConfirmCommand(
			paymentKey, preparedResult.orderCode(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_SYSTEM_ERROR.getStatus().value()),
			"error_code", PaymentError.PAYMENT_SYSTEM_ERROR.getCode()).count();

		// when
		assertThatThrownBy(() -> paymentConfirmer.confirm(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR);

		// then
		double after = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_SYSTEM_ERROR.getStatus().value()),
			"error_code", PaymentError.PAYMENT_SYSTEM_ERROR.getCode()).count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("복구 판정과 확정 재실행 건수가 태그와 함께 집계된다")
	void recoveryCountersAreRegistered() {
		// given
		recoveryMetrics.incrementDecision("DefinitiveFailure");
		recoveryMetrics.incrementRecovered();

		// when
		Counter decision = meterRegistry.find("payment.recovery.decision")
			.tag("decision", "DefinitiveFailure").counter();

		// then
		assertThat(decision).isNotNull();
		assertThat(decision.count()).isEqualTo(1.0);
		assertThat(meterRegistry.find("payment.recovery.recovered").counter().count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("수동 확인 대기 행과 오래된 진행 중 행이 게이지로 노출된다")
	void operationalGaugesReflectRows() {
		// given
		PaymentAttempt reviewed = paymentAttemptRepository.save(PaymentAttempt.startApproval(1L, "a-1", "k-1"));
		reviewed.markReviewRequired("REVIEW_RECOVERY_TIMEOUT", "최대 대기 초과");
		paymentAttemptRepository.save(reviewed);

		// when
		Gauge reviewRows = meterRegistry.find("payment.attempt.review_required_rows").gauge();

		// then
		assertThat(reviewRows).isNotNull();
		assertThat(reviewRows.value()).isEqualTo(1.0);
		assertThat(meterRegistry.find("payment.attempt.stale_in_progress_rows").gauge()).isNotNull();
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
