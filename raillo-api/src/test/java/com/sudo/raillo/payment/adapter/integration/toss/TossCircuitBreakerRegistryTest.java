package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;

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
}
