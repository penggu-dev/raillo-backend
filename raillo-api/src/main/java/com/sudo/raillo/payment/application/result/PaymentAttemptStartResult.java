package com.sudo.raillo.payment.application.result;

import com.sudo.raillo.payment.domain.PaymentAttempt;

/**
 * TX A 결과. 새 승인 시도를 획득한 호출만 외부 승인 API를 실행한다.
 *
 * <p>{@code attempt}는 TX A 안에서 검증을 마친 객체로, 트랜잭션 종료 후 detached 상태이지만 {@link PaymentAttempt}가 primitive/String 필드만 노출하므로 상위 호출자가 재조회 없이 그대로 사용해도 안전하다.
 */
public record PaymentAttemptStartResult(PaymentAttempt attempt, boolean created) {

	public Long attemptDbId() {
		return attempt.getId();
	}
}
