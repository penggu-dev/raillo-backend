package com.sudo.raillo.payment.adapter.integration.toss;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Toss 호출 브레이커. 사용자 조회와 Worker 조회는 커넥션 풀을 나눠 쓰므로 브레이커도 나눈다.
 *
 * <p>{@code toss-confirm}은 사용자와 Worker 경로가 공유한다. Worker는 confirm과 cancel을 호출하지
 * 않으므로 지금은 toss-confirm 창에 사용자 경로만 들어온다. Worker에 confirm 경로가 생기면 분리한다(#259).
 * 전제가 깨지면 Worker의 재시도가 사용자 confirm을 503으로 막을 수 있다.</p>
 *
 * <p>상태 전이 로그는 전이가 일어난 순간에만 찍힌다. {@code TossPaymentClient}의 차단 로그는 열린
 * 30초 동안 요청마다 찍히므로 양이 다르다.</p>
 */
@Slf4j
@Configuration
public class TossCircuitBreakerConfig {

	@Bean
	public CircuitBreaker tossConfirmBreaker(CircuitBreakerRegistry registry) {
		return withTransitionLog(registry.circuitBreaker("toss-confirm"));
	}

	@Bean
	public CircuitBreaker tossQueryUserBreaker(CircuitBreakerRegistry registry) {
		return withTransitionLog(registry.circuitBreaker("toss-query-user"));
	}

	@Bean
	public CircuitBreaker tossQueryWorkerBreaker(CircuitBreakerRegistry registry) {
		return withTransitionLog(registry.circuitBreaker("toss-query-worker"));
	}

	private static CircuitBreaker withTransitionLog(CircuitBreaker breaker) {
		breaker.getEventPublisher().onStateTransition(event -> {
			CircuitBreaker.State to = event.getStateTransition().getToState();
			if (to == CircuitBreaker.State.OPEN) {
				log.error("[TOSS] 회로 열림: name={}, transition={}", event.getCircuitBreakerName(),
					event.getStateTransition());
			} else {
				log.info("[TOSS] 회로 상태 전이: name={}, transition={}", event.getCircuitBreakerName(),
					event.getStateTransition());
			}
		});
		return breaker;
	}
}
