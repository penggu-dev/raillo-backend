package com.sudo.raillo.payment.adapter.integration.toss;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Toss 호출 브레이커. 사용자 조회와 Worker 조회는 커넥션 풀을 나눠 쓰므로 브레이커도 나눈다.
 *
 * <p>{@code toss-confirm}은 사용자와 Worker 경로가 공유한다. Worker는 confirm과 cancel을 호출하지
 * 않으므로 지금은 toss-confirm 창에 사용자 경로만 들어온다. Worker에 confirm 경로가 생기면 분리한다(#259).
 * 전제가 깨지면 Worker의 재시도가 사용자 confirm을 503으로 막을 수 있다.</p>
 */
@Configuration
public class TossCircuitBreakerConfig {

	@Bean
	public CircuitBreaker tossConfirmBreaker(CircuitBreakerRegistry registry) {
		return registry.circuitBreaker("toss-confirm");
	}

	@Bean
	public CircuitBreaker tossQueryUserBreaker(CircuitBreakerRegistry registry) {
		return registry.circuitBreaker("toss-query-user");
	}

	@Bean
	public CircuitBreaker tossQueryWorkerBreaker(CircuitBreakerRegistry registry) {
		return registry.circuitBreaker("toss-query-worker");
	}
}
