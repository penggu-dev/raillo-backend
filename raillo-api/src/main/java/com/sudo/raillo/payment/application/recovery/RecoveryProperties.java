package com.sudo.raillo.payment.application.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 기본값은 운영과 같다. 설정 블록이 없는 프로파일도 같은 값으로 뜨고, 스케줄러는 {@code enabled}로만 켜진다. */
@ConfigurationProperties(prefix = "raillo.payment.recovery")
public record RecoveryProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("30s") Duration pollingInterval,
	@DefaultValue("2m") Duration staleAfter,
	@DefaultValue("20") int batchSize,
	@DefaultValue("5m") Duration leaseDuration,
	@DefaultValue("1m") Duration initialBackoff,
	@DefaultValue("30m") Duration maxBackoff,
	@DefaultValue("24h") Duration maxWait
) {
	public RecoveryProperties {
		requirePositive("polling-interval", pollingInterval);
		requirePositive("stale-after", staleAfter);
		if (batchSize <= 0) {
			throw new IllegalArgumentException("raillo.payment.recovery.batch-size는 0보다 커야 한다: " + batchSize);
		}
		requirePositive("lease-duration", leaseDuration);
		requirePositive("initial-backoff", initialBackoff);
		requirePositive("max-backoff", maxBackoff);
		requirePositive("max-wait", maxWait);
	}

	private static void requirePositive(String key, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(
				"raillo.payment.recovery.%s는 0보다 큰 기간이어야 한다: %s".formatted(key, value));
		}
	}
}
