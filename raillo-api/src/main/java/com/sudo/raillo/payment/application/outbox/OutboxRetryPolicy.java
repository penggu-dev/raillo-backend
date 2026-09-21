package com.sudo.raillo.payment.application.outbox;

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
}
