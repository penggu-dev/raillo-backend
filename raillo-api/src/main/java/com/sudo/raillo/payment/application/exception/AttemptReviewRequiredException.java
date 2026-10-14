package com.sudo.raillo.payment.application.exception;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.AttemptError;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.Getter;

/**
 * 승인 확정(TX B)이 사람의 확인 없이는 끝낼 수 없다고 판단했을 때 던진다.
 *
 * <p>사유를 실어 보내는 이유는 마킹을 TX B 안에서 할 수 없어서다. {@code markReviewRequired}는
 * {@code REQUIRES_NEW}로 열고 같은 Payment 행을 다시 잠그므로, TX B 안에서 부르면 자기가 쥔 잠금을 기다린다.
 * 그래서 확정은 사유만 실어 중단하고, 잠금이 풀린 뒤 호출자가 마킹과 좌석 보호 해제를 한다.
 *
 * <p>응답 코드는 다른 수동 확인 경로와 같은 {@code PAYMENT_ATTEMPT_REVIEW_REQUIRED}다.
 */
@Getter
public class AttemptReviewRequiredException extends BusinessException {

	private final AttemptError attemptError;

	public AttemptReviewRequiredException(AttemptError attemptError) {
		super(PaymentError.PAYMENT_ATTEMPT_REVIEW_REQUIRED);
		this.attemptError = attemptError;
	}
}
