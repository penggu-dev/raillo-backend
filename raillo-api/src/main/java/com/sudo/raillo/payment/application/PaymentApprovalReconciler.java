package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.exception.PaymentGatewayException;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayQueryResult;
import com.sudo.raillo.payment.application.result.ReconcileDecision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * IN_PROGRESS로 남은 승인 시도를 게이트웨이에 조회해 무엇을 할지 판정한다. 게이트웨이 상태별 판정은
 * {@code reconcile}의 switch가 단일 원본이고 {@code PaymentApprovalReconcilerTest}가 행마다 고정한다.
 *
 * <p>상태를 바꾸지 않고 기록도 하지 않는다. 지금 판정을 쓰는 것은 사용자 재시도 경로
 * ({@link PaymentApprovalStarter})뿐이고, 같은 판정을 상태 기록으로 바꿀 Recovery Worker는 아직 없다(#270).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApprovalReconciler {

	private final PaymentGateway paymentGateway;

	public ReconcileDecision reconcile(PaymentConfirmTarget target) {
		if (target.paymentKey() == null || target.paymentKey().isBlank()) {
			// 조회하면 게이트웨이가 404로 답하고 그것을 "결제 없음 확정"으로 읽어 FAILED로 종결한다.
			// FAILED는 되살릴 수 없으므로(markReviewRequired가 IN_PROGRESS만 허용) 조회 전에 멈춘다.
			log.error("[승인 회복 판정 - 저장된 결제 키 없음] orderCode={}", target.orderCode());
			return new ReconcileDecision.ReviewRequired("REVIEW_PAYMENT_KEY_MISSING",
				"결제 시도에 저장된 결제 키가 없어 게이트웨이를 조회할 수 없습니다.");
		}

		GatewayQueryResult query;
		try {
			query = paymentGateway.query(target.paymentKey());
		} catch (PaymentGatewayException failure) {
			return fromQueryFailure(failure);
		}

		log.info("[승인 회복 판정 - 게이트웨이 상태 조회] paymentKey={}, status={}",
			target.paymentKey(), query.status());
		return switch (query.status()) {
			case DONE -> resolveDone(query.confirmResult(), target);
			case ABORTED, EXPIRED, CANCELED, PARTIAL_CANCELED -> new ReconcileDecision.DefinitiveFailure(
				"GATEWAY_" + query.status().name(), "게이트웨이가 확정 실패로 응답했습니다.");
			case READY, IN_PROGRESS, UNKNOWN -> new ReconcileDecision.StillPending(query.status());
			// 가상계좌를 쓰지 않으므로 도달하면 설계 가정이 깨진 것이다. 좌석을 유지하고 알린다.
			case WAITING_FOR_DEPOSIT -> new ReconcileDecision.UnexpectedStatus(query.status());
		};
	}

	/**
	 * 승인 완료 응답이 저장된 확정 대상과 일치하는지 본다. 어긋나면 자동 확정하지 않고 사람에게 넘긴다.
	 *
	 * <p>금액이나 주문번호가 다르면 다른 결제를 확정할 위험이 있고, 결제 키가 다르면 조회한 결제와 확정할
	 * 결제가 달라진다. 셋 중 하나라도 어긋나면 같은 사유로 묶는다. 어느 값이 어긋났는지는 로그에 남긴다.</p>
	 */
	private ReconcileDecision resolveDone(GatewayConfirmResult result, PaymentConfirmTarget target) {
		if (result.totalAmount().compareTo(target.amount()) != 0
			|| !result.orderCode().equals(target.orderCode())
			|| !result.paymentKey().equals(target.paymentKey())) {
			log.error("[승인 회복 판정 - 확정 대상 불일치] gateway=({}, {}, {}), stored=({}, {}, {})",
				result.paymentKey(), result.orderCode(), result.totalAmount(),
				target.paymentKey(), target.orderCode(), target.amount());
			return new ReconcileDecision.ReviewRequired(
				"REVIEW_RESULT_MISMATCH", "게이트웨이 응답이 저장된 결제 정보와 일치하지 않습니다.");
		}
		return new ReconcileDecision.Confirmable(result);
	}

	/**
	 * 조회 실패를 판정으로 바꾼다. 404만 "결제 없음"이 확정이고, 그 밖의 실패는 승인 여부를 알려주지 않는다.
	 */
	private ReconcileDecision fromQueryFailure(PaymentGatewayException failure) {
		if (failure.isResourceNotFound()) {
			log.warn("[승인 회복 판정 - 게이트웨이가 paymentKey를 찾지 못함] httpStatus={}, errorCode={}",
				failure.getHttpStatus(), failure.getErrorCode());
			return new ReconcileDecision.DefinitiveFailure(
				"GATEWAY_" + failure.getErrorCode(), failure.getMessage());
		}
		log.warn("[승인 회복 판정 - 조회 실패, 좌석 유지] httpStatus={}, errorCode={}",
			failure.getHttpStatus(), failure.getErrorCode());
		return new ReconcileDecision.QueryUncertain(uncertainCode(failure.getErrorCode()));
	}

	/** 결과 불명 사유 코드. 어댑터가 이미 접두사를 붙인 코드에는 덧붙이지 않는다. */
	private static String uncertainCode(String gatewayErrorCode) {
		if (gatewayErrorCode == null) {
			return PaymentGateway.QUERY_UNCERTAIN_PREFIX + "UNKNOWN";
		}
		return gatewayErrorCode.startsWith(PaymentGateway.QUERY_UNCERTAIN_PREFIX)
			? gatewayErrorCode
			: PaymentGateway.QUERY_UNCERTAIN_PREFIX + gatewayErrorCode;
	}
}
