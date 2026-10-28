package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("조회 재시도 정책")
class QueryRetryPolicyTest {

	private final QueryRetryPolicy policy =
		new QueryRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMillis(200));

	@Test
	@DisplayName("횟수 상한에 도달하면 더 시도하지 않는다")
	void stopsAtMaxAttempts() {
		assertThat(policy.exhausted(1, 0)).isFalse();
		assertThat(policy.exhausted(3, 0)).isFalse();
		assertThat(policy.exhausted(4, 0)).isTrue();
	}

	@Test
	@DisplayName("예산을 넘기면 횟수가 남아도 더 시도하지 않는다")
	void stopsWhenBudgetExceeded() {
		assertThat(policy.exhausted(2, 9_999)).isFalse();
		assertThat(policy.exhausted(2, 10_000)).isFalse();
		assertThat(policy.exhausted(2, 10_001)).isTrue();
	}

	@Test
	@DisplayName("백오프는 지수로 늘고 지터가 붙는다")
	void backoffIsExponentialWithJitter() {
		for (int i = 0; i < 50; i++) {
			assertThat(policy.backoffMillis(1)).isBetween(100L, 300L);
			assertThat(policy.backoffMillis(2)).isBetween(200L, 600L);
		}
	}

	@Test
	@DisplayName("backoffBase가 1ms 미만이면 생성을 거부한다")
	void rejectsSubMillisecondBackoffBase() {
		assertThatThrownBy(() -> new QueryRetryPolicy(3, Duration.ofSeconds(10), Duration.ofNanos(500_000)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
