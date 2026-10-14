package com.sudo.raillo.payment.application.recovery;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecoveryBackoffPolicyTest {

	private final RecoveryProperties properties = new RecoveryProperties(
		true, Duration.ofSeconds(30), Duration.ofMinutes(2), 20,
		Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(24));
	private final RecoveryBackoffPolicy policy = new RecoveryBackoffPolicy(properties);

	@Test
	@DisplayName("막 묵기 시작한 시도는 최초 간격만큼 뒤로 미룬다")
	void nextRetryAt_appliesInitialBackoff() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);

		// when
		LocalDateTime next = policy.nextRetryAt(now, now.minusMinutes(2));

		// then
		assertThat(next).isEqualTo(now.plusMinutes(1));
	}

	@Test
	@DisplayName("오래 묵은 시도일수록 간격이 두 배로 늘고 상한에서 멈춘다")
	void nextRetryAt_growsExponentiallyAndCaps() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);

		// when & then
		assertThat(policy.nextRetryAt(now, now.minusMinutes(4))).isEqualTo(now.plusMinutes(2));
		assertThat(policy.nextRetryAt(now, now.minusMinutes(16))).isEqualTo(now.plusMinutes(8));
		assertThat(policy.nextRetryAt(now, now.minusHours(5))).isEqualTo(now.plusMinutes(30));
	}

	@Test
	@DisplayName("최대 대기 시간을 넘긴 시도만 초과로 판정한다")
	void isMaxWaitExceeded_comparesAgainstCreatedAt() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);

		// when & then
		assertThat(policy.isMaxWaitExceeded(now.minusHours(23), now)).isFalse();
		assertThat(policy.isMaxWaitExceeded(now.minusHours(25), now)).isTrue();
	}
}
