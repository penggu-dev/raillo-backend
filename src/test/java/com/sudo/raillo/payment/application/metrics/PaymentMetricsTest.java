package com.sudo.raillo.payment.application.metrics;

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
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.PaymentFacade;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.type.CarType;

import io.micrometer.core.instrument.MeterRegistry;

@ServiceTest
class PaymentMetricsTest {

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private PaymentFacade paymentFacade;

	@MockitoBean
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MemberRepository memberRepository;



	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MeterRegistry meterRegistry;

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
		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(reservation.getReservationCode()));

		double before = meterRegistry.counter("payment_prepare_total").count();

		// when
		paymentFacade.preparePayment(request, memberNo);

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
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(reservation.getReservationCode())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, prepareResponse.orderId(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willReturn(tossResponse);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		double before = meterRegistry.counter("payment_confirm_success_total").count();

		// when
		paymentFacade.confirmPayment(confirmRequest, memberNo);

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
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(reservation.getReservationCode())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willThrow(new TossPaymentException(400, "INVALID_REQUEST", "test error"));

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "toss_error", "http_status", "400", "error_code", "INVALID_REQUEST").count();

		// when
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
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
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(reservation.getReservationCode())), memberNo);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), wrongAmount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "validation_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_AMOUNT_MISMATCH.getStatus().value()),
			"error_code", PaymentError.PAYMENT_AMOUNT_MISMATCH.getCode()).count();

		// when
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
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
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(reservation.getReservationCode())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willThrow(new RuntimeException("unexpected error"));

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error", "http_status", "500", "error_code", "UNKNOWN").count();

		// when
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
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
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(reservation.getReservationCode())), memberNo);

		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willThrow(new BusinessException(PaymentError.PAYMENT_SYSTEM_ERROR));

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		double before = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_SYSTEM_ERROR.getStatus().value()),
			"error_code", PaymentError.PAYMENT_SYSTEM_ERROR.getCode()).count();

		// when
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR);

		// then
		double after = meterRegistry.counter("payment_confirm_failure_total",
			"reason", "system_error",
			"http_status", String.valueOf(PaymentError.PAYMENT_SYSTEM_ERROR.getStatus().value()),
			"error_code", PaymentError.PAYMENT_SYSTEM_ERROR.getCode()).count();
		assertThat(after).isEqualTo(before + 1);
	}

	private Reservation createReservationWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 1);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		Long trainCarId = seats.get(0).getTrainCar().getId();

		Reservation reservation = reservationTestHelper.hold(
			memberNo,
			trainScheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(0)),
			List.of(PassengerType.ADULT));


		return reservation;
	}
}
