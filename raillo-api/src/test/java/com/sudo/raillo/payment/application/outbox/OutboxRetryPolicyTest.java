package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

	private final OutboxProperties props = new OutboxProperties(
		Duration.ofSeconds(5),
		10,
		Duration.ofSeconds(1),
		Duration.ofMinutes(1),
		5
	);
	private final OutboxRetryPolicy policy = new OutboxRetryPolicy(props);

	@Test
	@DisplayName("첫 재시도의 다음 실행 시각은 initialBackoff만큼 뒤")
	void nextRetryAt_firstRetry_appliesInitialBackoff() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		// when
		LocalDateTime next = policy.nextRetryAt(now, 0);

		// then
		assertThat(next).isEqualTo(now.plusSeconds(1));
	}

	@Test
	@DisplayName("재시도 횟수마다 2배씩 backoff가 증가한다")
	void nextRetryAt_exponentialBackoff() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		assertThat(policy.nextRetryAt(now, 1)).isEqualTo(now.plusSeconds(2));
		assertThat(policy.nextRetryAt(now, 2)).isEqualTo(now.plusSeconds(4));
		assertThat(policy.nextRetryAt(now, 3)).isEqualTo(now.plusSeconds(8));
	}

	@Test
	@DisplayName("maxBackoff를 초과하지 않는다")
	void nextRetryAt_cappedAtMax() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		LocalDateTime next = policy.nextRetryAt(now, 20);

		assertThat(next).isEqualTo(now.plusMinutes(1));
	}

	@Test
	@DisplayName("재시도 횟수가 maxRetries 이상이면 포기한다")
	void shouldGiveUp_afterMaxRetries() {
		assertThat(policy.shouldGiveUp(4)).isFalse();
		assertThat(policy.shouldGiveUp(5)).isTrue();
		assertThat(policy.shouldGiveUp(6)).isTrue();
	}
}
