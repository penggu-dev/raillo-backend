package com.sudo.raillo.payment.adapter.integration.toss;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 조회 한 건의 재시도 상한. 횟수와 경과 시간 두 축으로 막는다.
 *
 * <p>예산은 다음 시도를 시작하기 전에만 검사하므로 조회 한 건의 최대 비용은
 * {@code budget}에 호출 1회를 더한 값이다. 요청별 타임아웃 주입은 #305에서 다룬다.</p>
 */
record QueryRetryPolicy(int maxAttempts, Duration budget, Duration backoffBase) {

	QueryRetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts는 1 이상이어야 한다: " + maxAttempts);
		}
		if (budget == null || budget.isZero() || budget.isNegative()) {
			throw new IllegalArgumentException("budget은 0보다 큰 기간이어야 한다: " + budget);
		}
		if (backoffBase == null || backoffBase.toMillis() < 1) {
			throw new IllegalArgumentException("backoffBase는 1ms 이상이어야 한다: " + backoffBase);
		}
	}

	/** @param attempt 1부터 세는 이번 시도 번호 */
	boolean exhausted(int attempt, long elapsedMillis) {
		return attempt > maxAttempts || elapsedMillis > budget.toMillis();
	}

	/** 기준의 2의 거듭제곱을 중심으로 ±50% 지터. */
	long backoffMillis(int attempt) {
		long base = backoffBase.toMillis() * (1L << Math.min(attempt - 1, 20));
		return base / 2 + ThreadLocalRandom.current().nextLong(base);
	}
}
