package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;

/**
 * 승인 확정 대상 결제를 가리키는 세 값. 게이트웨이 응답이 이 값과 일치할 때만 자동 확정한다. 여기서 확정은
 * TX B({@link PaymentApprovalFinalizer})가 Payment를 PAID로 바꾸고 예매를 만드는 것을 말한다.
 *
 * <p>사용자 명령이 아니라 DB에서 읽는다. 명령을 기준으로 삼으면 사용자가 보낸 값과 응답이 서로 맞기만 해도
 * 통과하고, 명령이 없는 Recovery Worker(#270, 아직 없다)는 같은 경로를 쓸 수 없다.
 * TX B도 같은 세 값을 트랜잭션 안에서 직접 읽는다.</p>
 */
public record PaymentConfirmTarget(String paymentKey, String orderCode, BigDecimal amount) {

	public static PaymentConfirmTarget of(PaymentAttempt attempt, Payment payment) {
		return new PaymentConfirmTarget(attempt.getPaymentKey(), payment.getOrderCode(), payment.getAmount());
	}
}
