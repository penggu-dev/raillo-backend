package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import java.util.List;

/**
 * 승인 시작 단계의 결과. 세 가지 흐름 중 하나로 이어진다.
 *
 * <p>{@link #isAlreadyConfirmed()} — 같은 attemptId의 이전 승인이 이미 성공한 상태.
 * Toss 호출도 TX B 재실행도 없이 {@link #previousResult()}를 그대로 응답한다.
 *
 * <p>{@link #isRecovered()} — IN_PROGRESS로 남아 있던 attempt를 게이트웨이 재조회로 회복해
 * Toss 상에서 이미 DONE임을 확인한 상태. Toss confirm은 건너뛰고
 * {@link #recoveredGatewayResult()}를 그대로 TX B에 전달해 로컬 DB를 정정한다.
 *
 * <p>둘 다 아닌 경우엔 새 승인 흐름을 시작한다. 상위 서비스가 Toss confirm 호출 후 TX B로 확정한다.
 */
public record PaymentApprovalStart(
	Long paymentId,
	Long attemptDbId,
	List<Reservation> reservations,
	PaymentConfirmResult previousResult,
	GatewayConfirmResult recoveredGatewayResult
) {

	public static PaymentApprovalStart started(Long paymentId, Long attemptDbId, List<Reservation> reservations) {
		return new PaymentApprovalStart(paymentId, attemptDbId, reservations, null, null);
	}

	public static PaymentApprovalStart alreadyConfirmed(PaymentConfirmResult previousResult) {
		return new PaymentApprovalStart(null, null, null, previousResult, null);
	}

	public static PaymentApprovalStart recovered(
		Long paymentId,
		Long attemptDbId,
		List<Reservation> reservations,
		GatewayConfirmResult recoveredGatewayResult
	) {
		return new PaymentApprovalStart(paymentId, attemptDbId, reservations, null, recoveredGatewayResult);
	}

	public boolean isAlreadyConfirmed() {
		return previousResult != null;
	}

	public boolean isRecovered() {
		return recoveredGatewayResult != null;
	}
}
