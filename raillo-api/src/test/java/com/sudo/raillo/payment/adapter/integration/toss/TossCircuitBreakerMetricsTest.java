package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.support.annotation.ServiceTest;

import io.micrometer.core.instrument.MeterRegistry;

@ServiceTest
@DisplayName("서킷 브레이커 지표")
class TossCircuitBreakerMetricsTest {

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	@DisplayName("세 브레이커의 상태 게이지가 노출된다")
	void stateGaugeIsExposedForEachBreaker() {
		for (String name : new String[] {"toss-confirm", "toss-query-user", "toss-query-worker"}) {
			assertThat(meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", name).gauges())
				.as("%s의 상태 게이지", name)
				.isNotEmpty();
		}
	}

	@Test
	@DisplayName("open 때문에 막힌 호출 수가 노출된다")
	void notPermittedCounterIsExposed() {
		assertThat(meterRegistry.find("resilience4j.circuitbreaker.not.permitted.calls")
			.tag("name", "toss-confirm").counters()).isNotEmpty();
	}
}
