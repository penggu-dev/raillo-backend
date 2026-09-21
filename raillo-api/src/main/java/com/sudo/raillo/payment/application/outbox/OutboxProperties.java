package com.sudo.raillo.payment.application.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raillo.payment.outbox")
public record OutboxProperties(
	Duration pollingInterval,
	int batchSize,
	Duration initialBackoff,
	Duration maxBackoff,
	int maxRetries
) {
	public OutboxProperties {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be > 0");
		}
		if (maxRetries <= 0) {
			throw new IllegalArgumentException("maxRetries must be > 0");
		}
	}
}
