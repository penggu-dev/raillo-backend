package com.sudo.raillo.payment.adapter.integration.toss;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@ServiceTest
@DisplayName("Resilience4j 자동 설정")
class TossCircuitBreakerRegistryTest {

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Test
	@DisplayName("starter가 CircuitBreakerRegistry를 등록한다")
	void registryIsAutoConfigured() {
		assertThat(circuitBreakerRegistry).isNotNull();
	}

	/**
	 * yml의 instance 이름이 코드의 리터럴과 어긋나면 Resilience4j는 예외 없이 registry 기본값(60초,
	 * minimumNumberOfCalls 100)으로 브레이커를 만든다. 임계값을 직접 단언해 그 불일치를 잡는다.
	 */
	@Test
	@DisplayName("toss-query-user는 yml의 임계값으로 바인딩된다")
	void tossQueryUserBindsConfiguredThresholds() {
		// given
		CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("toss-query-user").getCircuitBreakerConfig();

		// then
		assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(3));
		assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
	}

	@Test
	@DisplayName("toss-confirm은 yml의 임계값으로 바인딩된다")
	void tossConfirmBindsConfiguredThresholds() {
		// given
		CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("toss-confirm").getCircuitBreakerConfig();

		// then
		assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(5));
		assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
	}

	@Test
	@DisplayName("toss-query-worker는 yml의 임계값으로 바인딩된다")
	void tossQueryWorkerBindsConfiguredThresholds() {
		// given
		CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("toss-query-worker").getCircuitBreakerConfig();

		// then
		assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
	}
}
