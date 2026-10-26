package com.sudo.raillo.payment.application.required;

import java.time.Duration;

/**
 * Outbox Worker가 실패·재시도 시점에 호출하는 관측 지표 required port.
 */
public interface OutboxMetrics {

	void incrementCleanupFailure();

	void incrementOutboxFailed();

	/** 재시도로 낫지 않는 원인이라 백오프 없이 FAILED로 보낸 건수. 알림은 이 지표에 건다. */
	void incrementOutboxNonRetryable();

	/** 백오프 후 재시도로 돌린 행 수. FAILED로 보낸 행은 세지 않는다. */
	void incrementRetryScheduled();

	/** 성공 처리한 행 수. */
	void incrementProcessed();

	/** 처리할 행이 있었던 poll 한 번의 실행 시간. 폴링 간격에 이 시간이 더해져 실제 주기가 된다. */
	void recordPollDuration(Duration elapsed);
}
