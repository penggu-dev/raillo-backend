package com.sudo.raillo.payment.application.result;

import com.sudo.raillo.payment.domain.PaymentAttempt;

/**
 * TX A 결과. 새 승인 시도를 획득한 호출만 외부 승인 API를 실행한다.
 *
 * <p>{@code attempt}는 TX A 안에서 검증을 마친 attempt 객체다. TX A 종료 후에는 detached 상태이지만
 * {@link PaymentAttempt}는 primitive/String 필드만 노출하므로 detached 상태에서도 안전하게 사용할 수 있다.
 * 상위 호출자는 이 값을 그대로 재사용해 재조회를 피한다.
 */
public record PaymentAttemptStartResult(PaymentAttempt attempt, boolean created) {

	public Long attemptDbId() {
		return attempt.getId();
	}
}
