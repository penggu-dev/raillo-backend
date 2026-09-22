package com.sudo.raillo.payment.application.required;

/**
 * Outbox Worker가 실패·재시도 시점에 호출하는 관측 지표 required port.
 */
public interface OutboxMetrics {

	void incrementCleanupFailure();

	void incrementOutboxFailed();
}
