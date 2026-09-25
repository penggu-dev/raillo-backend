package com.sudo.raillo.payment.adapter.observability;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;

class TossApiMetricsTest {

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final PoolingHttpClientConnectionManager connectionManager =
		PoolingHttpClientConnectionManagerBuilder.create().build();
	private final TossApiMetrics tossApiMetrics = new TossApiMetrics(meterRegistry, connectionManager);

	@Test
	@DisplayName("실패 시 toss_api_failure_total 카운터가 태그별로 증가한다")
	void incrementFailure_incrementsCounterWithTags() {
		// when
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("confirm", 500, "INTERNAL_ERROR");

		// then
		double invalidRequestCount = meterRegistry.counter("toss.api.failure",
			"operation", "confirm",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		assertThat(invalidRequestCount).isEqualTo(2);

		double internalErrorCount = meterRegistry.counter("toss.api.failure",
			"operation", "confirm",
			"http_status", "500",
			"toss_code", "INTERNAL_ERROR").count();
		assertThat(internalErrorCount).isEqualTo(1);
	}

	@Test
	@DisplayName("cancel operation 실패 시 카운터가 별도로 증가한다")
	void incrementFailure_cancelOperation_incrementsSeparately() {
		// when
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("cancel", 400, "INVALID_REQUEST");

		// then
		double confirmCount = meterRegistry.counter("toss.api.failure",
			"operation", "confirm",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		double cancelCount = meterRegistry.counter("toss.api.failure",
			"operation", "cancel",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		assertThat(confirmCount).isEqualTo(1);
		assertThat(cancelCount).isEqualTo(1);
	}

	@Test
	@DisplayName("tossCode가 null이면 UNKNOWN으로 정규화된다")
	void incrementFailure_nullTossCode_normalizedToUnknown() {
		// when
		tossApiMetrics.incrementFailure("confirm", 500, null);

		// then
		double count = meterRegistry.counter("toss.api.failure",
			"operation", "confirm",
			"http_status", "500",
			"toss_code", "UNKNOWN").count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("커넥션 풀 gauge 3개가 등록되고 초기 풀에서 모두 0을 반환한다")
	void connectionPoolGauges_areRegisteredWithZeroInitial() {
		// TossApiMetrics 생성 시(필드 초기화 시점) 등록된 gauge 검증
		Gauge active = meterRegistry.find("toss.pool.connections.active").gauge();
		Gauge idle = meterRegistry.find("toss.pool.connections.idle").gauge();
		Gauge pending = meterRegistry.find("toss.pool.connections.pending").gauge();

		assertThat(active).as("active gauge 등록").isNotNull();
		assertThat(idle).as("idle gauge 등록").isNotNull();
		assertThat(pending).as("pending gauge 등록").isNotNull();

		assertThat(active.value()).as("초기 leased").isZero();
		assertThat(idle.value()).as("초기 available").isZero();
		assertThat(pending.value()).as("초기 pending").isZero();
	}
}
