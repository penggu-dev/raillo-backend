package com.sudo.raillo.payment.application;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.exception.PaymentGatewayException;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 승인 유스케이스. 트랜잭션을 열지 않고 아래 세 단계를 순서대로 연결한다.
 *
 * <p>1단계 — {@link PaymentApprovalStarter}: 조회·검증 후 내부에서 TX A(REQUIRES_NEW)로
 * IN_PROGRESS attempt를 커밋한다. Starter 자체는 트랜잭션이 아니며 TX A는 그 안의 짧은 구간이다.
 * <p>2단계 — Toss 승인 요청: DB 트랜잭션 밖에서 호출한다. 확정 실패(4xx)만 PaymentAttempt를 FAILED로 마킹하고
 * Payment는 PENDING을 유지한다. 결과 불명(5xx·타임아웃)은 IN_PROGRESS로 남겨 회복 경로에 위임한다.
 * <p>3단계 — {@link PaymentApprovalFinalizer}: 이 메서드 전체가 TX B이며,
 * Order/Booking/Payment/Attempt/Outbox를 한 트랜잭션으로 커밋한다.
 *
 * <p>Reservation의 R→B 확정은 후속 PR에서 TX B의 Outbox 행을
 * {@link com.sudo.raillo.payment.application.outbox.PaymentOutboxWorker}가 비동기로 처리한다. 현재는 미구현 이벤트를 PENDING으로 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService implements PaymentConfirmer {

	private final PaymentApprovalStarter paymentApprovalStarter;
	private final PaymentApprovalFinalizer paymentApprovalFinalizer;
	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentGateway paymentGateway;

	@Override
	public PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo) {
		// 1단계: 승인 시작 (내부에서 TX A로 PaymentAttempt 등록)
		PaymentApprovalStart start = paymentApprovalStarter.start(command, memberNo);
		if (start.isAlreadyConfirmed()) {
			return start.previousResult();
		}

		// 2단계: Toss 승인 요청 (DB 트랜잭션 밖)
		// IN_PROGRESS 재조회로 이미 DONE을 확인한 경우엔 Toss confirm 없이 재조회 결과로 확정한다.
		GatewayConfirmResult approval;
		if (start.isRecovered()) {
			approval = start.recoveredGatewayResult();
		} else {
			try {
				approval = paymentGateway.confirm(command);
			} catch (PaymentGatewayException failure) {
				if (failure.isDefinitiveFailure()) {
					// Toss 4xx: 명확한 실패 → PaymentAttempt만 FAILED로 마킹. Payment는 PENDING 유지.
					paymentAttemptManager.markFailedInNewTransaction(
						start.attemptDbId(), failure.getErrorCode(), failure.getMessage()
					);
				}
				// 결과 불명(5xx/timeout)은 IN_PROGRESS로 남기고 회복 경로(유저 재시도 · Recovery Worker)에 위임한다.
				throw failure;
			}
		}

		// 3단계: 승인 확정 — 이 메서드 전체가 TX B (Order/Booking/Payment/Attempt/Outbox 원자 커밋)
		PaymentConfirmResult result = paymentApprovalFinalizer.finalizeApproval(
			start.paymentId(), start.attemptDbId(), command, approval, start.reservations()
		);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", start.paymentId(), command.orderId());
		return result;
	}

}
