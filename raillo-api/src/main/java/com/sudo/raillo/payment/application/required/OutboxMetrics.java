package com.sudo.raillo.payment.application.required;

/**
 * Outbox Worker가 실패·재시도 시점에 호출하는 관측 지표 required port.
 */
public interface OutboxMetrics {

	void incrementCleanupFailure();

	void incrementOutboxFailed();

	/** 재시도로 낫지 않는 원인이라 백오프 없이 FAILED로 보낸 건수. 알림은 이 지표에 건다. */
	void incrementOutboxNonRetryable();
}
