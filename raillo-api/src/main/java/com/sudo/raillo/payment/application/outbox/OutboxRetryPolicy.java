package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.global.exception.ErrorCodeCarrier;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class OutboxRetryPolicy {

	private final OutboxProperties properties;

	public OutboxRetryPolicy(OutboxProperties properties) {
		this.properties = properties;
	}

	public LocalDateTime nextRetryAt(LocalDateTime now, int retryCount) {
		long initialMs = properties.initialBackoff().toMillis();
		long maxMs = properties.maxBackoff().toMillis();
		long backoffMs = Math.min(initialMs * (1L << Math.min(retryCount, 30)), maxMs);
		return now.plus(Duration.ofMillis(backoffMs));
	}

	public boolean shouldGiveUp(int retryCount) {
		return retryCount >= properties.maxRetries();
	}

	/**
	 * 이 원인으로 실패한 건을 다시 처리해볼 가치가 있는지.
	 *
	 * <p>에러 코드를 싣지 않은 실패(연결 끊김, 타임아웃 등)는 모두 재시도 대상으로 본다. 재시도 불가는 에러 코드가
	 * {@link com.sudo.raillo.global.exception.ErrorCode#retryable()}로 직접 선언한 경우뿐이다.</p>
	 *
	 * <p>특정 예외 클래스가 아니라 {@link ErrorCodeCarrier}로 분기한다. 에러 코드를 싣는 예외가 셋이고
	 * 공통 부모가 {@code RuntimeException}뿐이라, 클래스를 나열하면 새로 생긴 예외가 조용히 빠진다.</p>
	 */
	public boolean isRetryable(Throwable cause) {
		return !(cause instanceof ErrorCodeCarrier carrier) || carrier.getErrorCode().retryable();
	}
}
