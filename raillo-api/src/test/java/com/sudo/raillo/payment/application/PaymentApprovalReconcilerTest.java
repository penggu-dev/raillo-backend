package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
import com.sudo.raillo.payment.application.exception.DeliveryPhase;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayPaymentStatus;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayQueryResult;
import com.sudo.raillo.payment.application.result.ReconcileDecision;
import com.sudo.raillo.payment.domain.PaymentMethod;

/**
 * 게이트웨이 상태별 판정을 행마다 고정한다. 판정은 상태를 바꾸지 않으므로 DB 없이 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentApprovalReconciler - 승인 회복 판정")
class PaymentApprovalReconcilerTest {

	private static final String PAYMENT_KEY = "toss_pk_reconcile";
	private static final String ORDER_CODE = "ORDER-1";
	private static final BigDecimal AMOUNT = BigDecimal.valueOf(50000);

	@InjectMocks
	private PaymentApprovalReconciler reconciler;

	@Mock
	private PaymentGateway paymentGateway;

	private static GatewayConfirmResult confirmResult() {
		return new GatewayConfirmResult(PAYMENT_KEY, ORDER_CODE, AMOUNT, PaymentMethod.CREDIT_CARD);
	}

	/** 게이트웨이 응답과 일치하는 확정 대상. */
	private static PaymentConfirmTarget matchingTarget() {
		return new PaymentConfirmTarget(PAYMENT_KEY, ORDER_CODE, AMOUNT);
	}

	private ReconcileDecision reconcileWith(GatewayQueryResult queryResult) {
		return reconcileWith(queryResult, matchingTarget());
	}

	private ReconcileDecision reconcileWith(GatewayQueryResult queryResult, PaymentConfirmTarget target) {
		when(paymentGateway.query(anyString())).thenReturn(queryResult);
		return reconciler.reconcile(target);
	}

	private ReconcileDecision reconcileThrowing(RuntimeException failure) {
		when(paymentGateway.query(anyString())).thenThrow(failure);
		return reconciler.reconcile(matchingTarget());
	}

	@Test
	@DisplayName("게이트웨이가 DONE이면 확정 가능으로 판정하고 승인 결과를 함께 돌려준다")
	void gatewayDone_returnsConfirmable() {
		// given & when
		ReconcileDecision decision = reconcileWith(GatewayQueryResult.done(confirmResult()));

		// then
		assertThat(decision).isEqualTo(new ReconcileDecision.Confirmable(confirmResult()));
	}

	@Nested
	@DisplayName("확정 실패 상태")
	class DefinitiveFailureStatuses {

		@Test
		@DisplayName("ABORTED는 확정 실패로 판정하고 사유에 상태를 담는다")
		void aborted() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.ABORTED));

			// then
			assertThat(decision).isInstanceOf(ReconcileDecision.DefinitiveFailure.class);
			assertThat(((ReconcileDecision.DefinitiveFailure)decision).errorCode()).isEqualTo("GATEWAY_ABORTED");
		}

		@Test
		@DisplayName("EXPIRED는 확정 실패로 판정한다")
		void expired() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.EXPIRED));

			// then
			assertThat(((ReconcileDecision.DefinitiveFailure)decision).errorCode()).isEqualTo("GATEWAY_EXPIRED");
		}

		@Test
		@DisplayName("CANCELED는 확정 실패로 판정한다")
		void canceled() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.CANCELED));

			// then
			assertThat(((ReconcileDecision.DefinitiveFailure)decision).errorCode()).isEqualTo("GATEWAY_CANCELED");
		}

		@Test
		@DisplayName("PARTIAL_CANCELED는 확정 실패로 판정한다")
		void partialCanceled() {
			// given & when
			ReconcileDecision decision = reconcileWith(
				GatewayQueryResult.of(GatewayPaymentStatus.PARTIAL_CANCELED));

			// then
			assertThat(((ReconcileDecision.DefinitiveFailure)decision).errorCode())
				.isEqualTo("GATEWAY_PARTIAL_CANCELED");
		}
	}

	@Nested
	@DisplayName("진행 중 상태")
	class PendingStatuses {

		@Test
		@DisplayName("READY는 진행 중으로 판정해 좌석을 유지한다")
		void ready() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.READY));

			// then
			assertThat(decision).isEqualTo(new ReconcileDecision.StillPending(GatewayPaymentStatus.READY));
		}

		@Test
		@DisplayName("IN_PROGRESS는 진행 중으로 판정한다")
		void inProgress() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.IN_PROGRESS));

			// then
			assertThat(decision).isEqualTo(new ReconcileDecision.StillPending(GatewayPaymentStatus.IN_PROGRESS));
		}

		@Test
		@DisplayName("UNKNOWN은 진행 중으로 판정해 좌석을 유지한다")
		void unknown() {
			// given & when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.of(GatewayPaymentStatus.UNKNOWN));

			// then
			assertThat(decision).isEqualTo(new ReconcileDecision.StillPending(GatewayPaymentStatus.UNKNOWN));
		}
	}

	@Test
	@DisplayName("가상계좌 입금 대기는 이 프로젝트가 쓰지 않는 상태이므로 예상 밖 상태로 판정한다")
	void waitingForDeposit_returnsUnexpectedStatus() {
		// given & when
		ReconcileDecision decision = reconcileWith(
			GatewayQueryResult.of(GatewayPaymentStatus.WAITING_FOR_DEPOSIT));

		// then
		assertThat(decision).isEqualTo(
			new ReconcileDecision.UnexpectedStatus(GatewayPaymentStatus.WAITING_FOR_DEPOSIT));
	}

	@Nested
	@DisplayName("확정 대상 불일치")
	class ResultMismatch {

		@Test
		@DisplayName("게이트웨이 금액이 저장된 금액과 다르면 수동 확인 대상으로 판정한다")
		void amountMismatch() {
			// given
			PaymentConfirmTarget differentAmount = new PaymentConfirmTarget(PAYMENT_KEY, ORDER_CODE, BigDecimal.ONE);

			// when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.done(confirmResult()), differentAmount);

			// then
			assertThat(decision).isInstanceOf(ReconcileDecision.ReviewRequired.class);
			assertThat(((ReconcileDecision.ReviewRequired)decision).errorCode())
				.isEqualTo("REVIEW_RESULT_MISMATCH");
		}

		@Test
		@DisplayName("게이트웨이 주문번호가 저장된 주문번호와 다르면 수동 확인 대상으로 판정한다")
		void orderCodeMismatch() {
			// given
			PaymentConfirmTarget differentOrder = new PaymentConfirmTarget(PAYMENT_KEY, "OTHER-ORDER", AMOUNT);

			// when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.done(confirmResult()), differentOrder);

			// then
			assertThat(((ReconcileDecision.ReviewRequired)decision).errorCode())
				.isEqualTo("REVIEW_RESULT_MISMATCH");
		}

		@Test
		@DisplayName("게이트웨이 결제 키가 저장된 키와 다르면 수동 확인 대상으로 판정한다")
		void paymentKeyMismatch() {
			// given
			PaymentConfirmTarget differentKey = new PaymentConfirmTarget("other_pk", ORDER_CODE, AMOUNT);

			// when
			ReconcileDecision decision = reconcileWith(GatewayQueryResult.done(confirmResult()), differentKey);

			// then
			assertThat(((ReconcileDecision.ReviewRequired)decision).errorCode())
				.isEqualTo("REVIEW_RESULT_MISMATCH");
		}
	}

	@Test
	@DisplayName("조회가 404면 결제 없음이 확정이므로 확정 실패로 판정한다")
	void queryNotFound_returnsDefinitiveFailure() {
		// given & when
		ReconcileDecision decision = reconcileThrowing(
			new TossPaymentException(404, "NOT_FOUND_PAYMENT", "결제를 찾을 수 없습니다."));

		// then
		assertThat(decision).isInstanceOf(ReconcileDecision.DefinitiveFailure.class);
		assertThat(((ReconcileDecision.DefinitiveFailure)decision).errorCode())
			.isEqualTo("GATEWAY_NOT_FOUND_PAYMENT");
	}

	@Test
	@DisplayName("조회가 5xx면 승인 여부를 알 수 없으므로 결과 불명으로 판정하고 좌석을 유지한다")
	void queryServerError_returnsQueryUncertain() {
		// given & when
		ReconcileDecision decision = reconcileThrowing(
			new TossPaymentException(503, "INTERNAL_SERVER_ERROR", "게이트웨이 오류"));

		// then
		assertThat(decision).isEqualTo(
			new ReconcileDecision.QueryUncertain("QUERY_UNCERTAIN_INTERNAL_SERVER_ERROR"));
	}

	@Test
	@DisplayName("클라이언트가 이미 QUERY_UNCERTAIN_ 접두사를 붙인 코드에는 접두사를 덧붙이지 않는다")
	void queryNoResponse_doesNotDoublePrefix() {
		// given 어댑터가 붙이는 접두사와 같은 상수로 입력을 만들어 한쪽만 바뀌면 이 테스트가 깨지게 한다
		String adapterCode = PaymentGateway.QUERY_UNCERTAIN_PREFIX + "TIMEOUT";
		ReconcileDecision decision = reconcileThrowing(
			new TossPaymentException(0, adapterCode, "조회 타임아웃", DeliveryPhase.NO_RESPONSE));

		// then
		assertThat(decision).isEqualTo(new ReconcileDecision.QueryUncertain(adapterCode));
	}

	@Test
	@DisplayName("저장된 결제 키가 없으면 게이트웨이를 조회하지 않고 수동 확인 대상으로 판정한다")
	void missingPaymentKey_returnsReviewRequiredWithoutQuery() {
		// given 과거 행에는 paymentKey가 없을 수 있다. 조회하면 Toss가 404로 답해 FAILED로 오종결된다
		PaymentConfirmTarget missingKey = new PaymentConfirmTarget(null, ORDER_CODE, AMOUNT);

		// when
		ReconcileDecision decision = reconciler.reconcile(missingKey);

		// then
		assertThat(decision).isEqualTo(new ReconcileDecision.ReviewRequired(
			"REVIEW_PAYMENT_KEY_MISSING", "결제 시도에 저장된 결제 키가 없어 게이트웨이를 조회할 수 없습니다."));
		verifyNoInteractions(paymentGateway);
	}

	@Test
	@DisplayName("저장된 결제 키가 공백 문자뿐이면 게이트웨이를 조회하지 않고 수동 확인 대상으로 판정한다")
	void blankPaymentKey_returnsReviewRequiredWithoutQuery() {
		// given
		PaymentConfirmTarget blankKey = new PaymentConfirmTarget("   ", ORDER_CODE, AMOUNT);

		// when
		ReconcileDecision decision = reconciler.reconcile(blankKey);

		// then
		assertThat(decision).isInstanceOf(ReconcileDecision.ReviewRequired.class);
		assertThat(((ReconcileDecision.ReviewRequired) decision).errorCode())
			.isEqualTo("REVIEW_PAYMENT_KEY_MISSING");
		verifyNoInteractions(paymentGateway);
	}

	@Test
	@DisplayName("게이트웨이 조회는 확정 대상의 결제 키로 한다")
	void queriesWithConfirmTargetPaymentKey() {
		// given
		when(paymentGateway.query(PAYMENT_KEY)).thenReturn(GatewayQueryResult.done(confirmResult()));

		// when
		reconciler.reconcile(matchingTarget());

		// then 시그니처가 넓어졌으므로 엉뚱한 필드로 조회하는 회귀를 막는다
		verify(paymentGateway).query(PAYMENT_KEY);
	}

	@Test
	@DisplayName("조회가 404가 아닌 4xx로 실패하면 승인 여부를 알 수 없으므로 결과 불명으로 판정한다")
	void queryClientError_returnsQueryUncertain() {
		// given 인증 오류나 rate limit은 결제 상태를 알려주지 않는다
		ReconcileDecision decision = reconcileThrowing(
			new TossPaymentException(401, "UNAUTHORIZED_KEY", "인증 실패"));

		// then
		assertThat(decision).isInstanceOf(ReconcileDecision.QueryUncertain.class);
	}
}
