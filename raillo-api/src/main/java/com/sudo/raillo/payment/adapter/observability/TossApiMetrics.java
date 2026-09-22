package com.sudo.raillo.payment.adapter.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Toss API 호출 관측 지표.
 *
 * <ul>
 *   <li>{@code toss_api_failure_total} (counter): 호출 실패 건수 (operation, http_status, toss_code 태그)</li>
 *   <li>{@code toss_api_in_flight} (gauge): 현재 진행 중인 요청 수 (operation 태그). {@link TossApiTimerAspect}가 갱신</li>
 *   <li>{@code toss_pool_connections_active} (gauge): 커넥션 풀에서 사용 중(leased) 커넥션 수</li>
 *   <li>{@code toss_pool_connections_idle} (gauge): 커넥션 풀 유휴(available) 커넥션 수</li>
 *   <li>{@code toss_pool_connections_pending} (gauge): 커넥션 획득 대기(pending) 요청 수. 값이 지속적으로 잡히면 풀 크기 상향 신호</li>
 * </ul>
 *
 * <p>응답 시간 histogram({@code toss_api_duration_seconds})은 {@link TossApiTimerAspect}가 별도 등록.</p>
 */
@Component
public class TossApiMetrics {

	private final MeterRegistry meterRegistry;
	private final Map<String, AtomicInteger> inFlightByOperation = new ConcurrentHashMap<>();

	public TossApiMetrics(MeterRegistry meterRegistry, PoolingHttpClientConnectionManager tossHttpConnectionManager) {
		this.meterRegistry = meterRegistry;

		Gauge.builder("toss_pool_connections_active", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getLeased())
			.description("Toss HTTP 커넥션 풀 사용 중(leased) 커넥션 수")
			.register(meterRegistry);
		Gauge.builder("toss_pool_connections_idle", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getAvailable())
			.description("Toss HTTP 커넥션 풀 유휴(available) 커넥션 수")
			.register(meterRegistry);
		Gauge.builder("toss_pool_connections_pending", tossHttpConnectionManager,
				manager -> manager.getTotalStats().getPending())
			.description("Toss HTTP 커넥션 획득 대기(pending) 요청 수")
			.register(meterRegistry);
	}

	public void incrementInFlight(String operation) {
		inFlightGaugeFor(operation).incrementAndGet();
	}

	public void decrementInFlight(String operation) {
		AtomicInteger counter = inFlightByOperation.get(operation);
		if (counter != null) {
			counter.decrementAndGet();
		}
	}

	public void incrementFailure(String operation, int httpStatus, String tossCode) {
		Counter.builder("toss_api_failure_total")
			.description("Toss API 호출 실패 건수")
			.tag("operation", operation)
			.tag("http_status", String.valueOf(httpStatus))
			.tag("toss_code", tossCode != null ? tossCode : "UNKNOWN")
			.register(meterRegistry)
			.increment();
	}

	private AtomicInteger inFlightGaugeFor(String operation) {
		return inFlightByOperation.computeIfAbsent(operation, op -> {
			AtomicInteger counter = new AtomicInteger(0);
			Gauge.builder("toss_api_in_flight", counter, AtomicInteger::get)
				.description("Toss API 진행 중 요청 수")
				.tag("operation", op)
				.register(meterRegistry);
			return counter;
		});
	}
}
