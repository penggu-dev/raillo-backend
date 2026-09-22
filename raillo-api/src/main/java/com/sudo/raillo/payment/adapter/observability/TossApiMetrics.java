package com.sudo.raillo.payment.adapter.observability;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Toss API 호출 관측 지표. 미터 이름은 Micrometer 관례(dot notation)를 따르며, Prometheus 레지스트리가 다음과 같이 노출한다.
 *
 * <ul>
 *   <li>{@code toss.api.failure} → {@code toss_api_failure_total} (counter): 호출 실패 건수 (operation, http_status, toss_code 태그)</li>
 *   <li>{@code toss.pool.connections.active} → {@code toss_pool_connections_active} (gauge): 커넥션 풀에서 사용 중(leased) 커넥션 수</li>
 *   <li>{@code toss.pool.connections.idle} → {@code toss_pool_connections_idle} (gauge): 커넥션 풀 유휴(available) 커넥션 수</li>
 *   <li>{@code toss.pool.connections.pending} → {@code toss_pool_connections_pending} (gauge): 커넥션 획득 대기(pending) 요청 수. 값이 지속적으로 잡히면 풀 크기 상향 신호</li>
 * </ul>
 *
 * <p>응답 시간 histogram({@code toss.api.duration} → {@code toss_api_duration_seconds})과 진행 중 요청의 age 분포
 * ({@code toss.api.active} → {@code toss_api_active_seconds_*})는 {@link TossApiTimerAspect}가 등록한다.</p>
 */
@Component
public class TossApiMetrics {

	private final MeterRegistry meterRegistry;

	public TossApiMetrics(MeterRegistry meterRegistry, PoolingHttpClientConnectionManager tossHttpConnectionManager) {
		this.meterRegistry = meterRegistry;

		Gauge.builder("toss.pool.connections.active", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getLeased())
			.description("Toss HTTP 커넥션 풀 사용 중(leased) 커넥션 수")
			.register(meterRegistry);
		Gauge.builder("toss.pool.connections.idle", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getAvailable())
			.description("Toss HTTP 커넥션 풀 유휴(available) 커넥션 수")
			.register(meterRegistry);
		Gauge.builder("toss.pool.connections.pending", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getPending())
			.description("Toss HTTP 커넥션 획득 대기(pending) 요청 수")
			.register(meterRegistry);
	}

	public void incrementFailure(String operation, int httpStatus, String tossCode) {
		Counter.builder("toss.api.failure")
			.description("Toss API 호출 실패 건수")
			.tag("operation", operation)
			.tag("http_status", String.valueOf(httpStatus))
			.tag("toss_code", tossCode != null ? tossCode : "UNKNOWN")
			.register(meterRegistry)
			.increment();
	}
}
