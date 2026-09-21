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
 * <p>1. TX A — {@link PaymentApprovalStarter}: 조회·검증 후 IN_PROGRESS attempt를 커밋한다.
 * <p>2. Toss 승인 요청 — DB 트랜잭션 밖에서 호출한다. 확정 실패(4xx)만 FAILED로 마킹하고,
 * 결과 불명(5xx·타임아웃)은 IN_PROGRESS로 남겨 Recovery 대상으로 둔다.
 * <p>3. TX B — {@link PaymentApprovalFinalizer}: Order/Booking/Payment/Attempt/Outbox를 함께 커밋한다.
 *
 * <p>PendingBooking 삭제·좌석 Hold 해제는 TX B에서 저장한 Outbox 행을
 * {@link com.sudo.raillo.payment.application.outbox.PaymentOutboxWorker}가 비동기로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService implements PaymentConfirmer {

	private final PaymentApprovalStarter paymentApprovalStarter;
	private final PaymentApprovalFinalizer paymentApprovalFinalizer;
	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentModifier paymentModifier;
	private final PaymentGateway paymentGateway;

	@Override
	public PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo) {
		// 1. TX A - 승인 시작
		PaymentApprovalStart start = paymentApprovalStarter.start(command, memberNo);
		if (start.isAlreadyConfirmed()) {
			return start.previousResult();
		}

		// 2. Toss 승인 요청 (DB 트랜잭션 없음)
		GatewayConfirmResult approval = requestTossApproval(command, start.paymentId(), start.attemptDbId());

		// 3. TX B - 승인 확정 (Outbox INSERT까지 원자적으로 커밋)
		PaymentConfirmResult result = paymentApprovalFinalizer.finalizeApproval(
			start.paymentId(), start.attemptDbId(), command, approval, start.pendingBookings()
		);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", start.paymentId(), command.orderId());
		return result;
	}

	private GatewayConfirmResult requestTossApproval(PaymentConfirmCommand command, Long paymentId, Long attemptDbId) {
		// TODO(#270): 응답 유실·타임아웃은 IN_PROGRESS로 유지하고 Recovery Worker가 Toss 조회로 확정한다.
		try {
			return paymentGateway.confirm(command);
		} catch (PaymentGatewayException e) {
			handleGatewayFailure(paymentId, attemptDbId, command, e);
			throw e;
		}
	}

	private void handleGatewayFailure(
		Long paymentId,
		Long attemptDbId,
		PaymentConfirmCommand command,
		PaymentGatewayException exception
	) {
		if (!exception.isDefinitiveFailure()) {
			log.warn("[게이트웨이 결제 승인 결과 불명] IN_PROGRESS 유지: orderCode={}, httpStatus={}, code={}",
				command.orderId(), exception.getHttpStatus(), exception.getErrorCode());
			return;
		}

		paymentAttemptManager.markFailedInNewTransaction(
			attemptDbId, exception.getErrorCode(), exception.getMessage()
		);
		paymentModifier.failPaymentInNewTransaction(
			paymentId, exception.getErrorCode(), exception.getMessage()
		);
		log.info("[게이트웨이 결제 승인 확정 실패] orderCode={}, httpStatus={}, code={}, message={}",
			command.orderId(), exception.getHttpStatus(), exception.getErrorCode(), exception.getMessage());
	}
}
