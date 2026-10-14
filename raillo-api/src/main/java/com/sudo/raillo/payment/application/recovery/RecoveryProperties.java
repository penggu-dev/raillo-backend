package com.sudo.raillo.payment.application.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raillo.payment.recovery")
public record RecoveryProperties(
	boolean enabled,
	Duration pollingInterval,
	Duration staleAfter,
	int batchSize,
	Duration leaseDuration,
	Duration initialBackoff,
	Duration maxBackoff,
	Duration maxWait
) {
	public RecoveryProperties {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be > 0");
		}
	}
}
