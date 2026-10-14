package com.sudo.raillo.payment.application.recovery;

import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 묵은 시간으로 다음 조회 시각을 정한다. attempt에 조회 횟수가 없어 경과 시간을 지표로 쓴다. */
@Component
public class RecoveryBackoffPolicy {

	private final RecoveryProperties properties;

	public RecoveryBackoffPolicy(RecoveryProperties properties) {
		this.properties = properties;
	}

	/** 경과 시간이 암시하는 간격의 절반을 다음 간격으로 쓴다. */
	public LocalDateTime nextRetryAt(LocalDateTime now, LocalDateTime createdAt) {
		long initialSeconds = properties.initialBackoff().toSeconds();
		long elapsedSeconds = Math.max(Duration.between(createdAt, now).toSeconds(), initialSeconds);
		long ratio = elapsedSeconds / initialSeconds;
		long doublings = Math.max(0, 63 - Long.numberOfLeadingZeros(ratio) - 1);
		Duration backoff = properties.initialBackoff().multipliedBy(1L << doublings);
		return now.plus(backoff.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : backoff);
	}

	public boolean isMaxWaitExceeded(LocalDateTime createdAt, LocalDateTime now) {
		return Duration.between(createdAt, now).compareTo(properties.maxWait()) > 0;
	}
}
