package com.sudo.raillo.payment.application.command;

import com.sudo.raillo.payment.application.PaymentAttemptIds;
import java.math.BigDecimal;

/**
 * 결제 승인 요청 커맨드. attemptId는 서버가 paymentKey에서 결정적으로 파생한다.
 * 파생 규칙은 {@link PaymentAttemptIds#forApproval(String)}에 있다.
 */
public record PaymentConfirmCommand(
	String paymentKey,
	String orderId,
	BigDecimal amount
) {

	/** paymentKey에서 SHA-256으로 파생된 결제 시도 idempotency key. 한 결제창 세션당 하나. */
	public String attemptId() {
		return PaymentAttemptIds.forApproval(paymentKey);
	}
}
