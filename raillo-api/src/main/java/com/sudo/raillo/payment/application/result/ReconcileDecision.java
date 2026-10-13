package com.sudo.raillo.payment.application.result;

import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayPaymentStatus;

/**
 * IN_PROGRESS attempt 하나에 대한 회복 판정. 상태를 바꾸지 않고 무엇을 할지만 돌려준다.
 *
 * <p>sealed로 두어 소비자가 분기를 빠뜨리면 컴파일이 멈춘다. 지금 소비자는 사용자 재시도 경로뿐이고,
 * 같은 판정을 상태 기록으로 바꿀 Recovery Worker는 아직 없다(#270).</p>
 */
public sealed interface ReconcileDecision {

	/** 게이트웨이가 승인 완료로 응답했고 확정 대상이 일치한다. 호출자가 TX B로 확정한다. */
	record Confirmable(GatewayConfirmResult gatewayResult) implements ReconcileDecision {}

	/** 게이트웨이가 확정 실패로 응답했다. attempt를 FAILED로 기록한다. */
	record DefinitiveFailure(String errorCode, String message) implements ReconcileDecision {}

	/** 승인은 됐지만 자동 확정할 수 없다. attempt를 REVIEW_REQUIRED로 기록하고 사람이 처리한다. */
	record ReviewRequired(String errorCode, String message) implements ReconcileDecision {}

	/** 게이트웨이가 아직 처리 중이다. 좌석을 유지하고 다음 조회를 기다린다. */
	record StillPending(GatewayPaymentStatus status) implements ReconcileDecision {}

	/** 조회 자체가 결과를 알려주지 못했다. 좌석을 유지하고 다음 조회를 기다린다. */
	record QueryUncertain(String errorCode) implements ReconcileDecision {}

	/** 이 프로젝트가 쓰지 않는 상태가 왔다. 설계 가정이 깨진 것이므로 좌석을 유지하고 알린다. */
	record UnexpectedStatus(GatewayPaymentStatus status) implements ReconcileDecision {}
}
