package com.sudo.raillo.payment.adapter.integration.toss;

import java.io.IOException;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * Toss API 호출용 GET 전용 재시도 전략.
 *
 * <p><strong>왜 GET만 재시도하나</strong>: POST(confirm/cancel)는 idempotent하지 않다. 서버가 이미 처리했는데 응답만 유실된
 * 상태에서 재시도하면 중복 승인·중복 취소 위험. 서버 재시도 대신 IN_PROGRESS attempt를 남겨 사용자 재시도 경로나 Recovery
 * Worker(#270)에 위임한다. GET(query)은 서버 상태를 변경하지 않으므로 자동 재시도가 안전.</p>
 *
 * <p><strong>재시도 조건</strong>:</p>
 * <ul>
 *   <li>I/O 예외 (SocketTimeoutException, Connection reset 등) — 응답 유실 or 네트워크 순간 장애</li>
 *   <li>5xx 응답 (500, 502, 503, 504 등) — Toss 서버 일시 오류</li>
 *   <li>4xx는 재시도 대상 아님 — 요청 자체 문제라 재시도해도 결과 동일</li>
 * </ul>
 *
 * <p><strong>backoff</strong>: 지수, 200ms → 400ms. 최대 2회 재시도(총 3회 호출).</p>
 */
public class TossGetRetryStrategy implements HttpRequestRetryStrategy {

	private static final int MAX_RETRIES = 2;
	private static final long INITIAL_BACKOFF_MS = 200L;

	@Override
	public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
		return execCount <= MAX_RETRIES && isGet(request);
	}

	@Override
	public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
		if (execCount > MAX_RETRIES) {
			return false;
		}
		int status = response.getCode();
		if (status < 500 || status >= 600) {
			return false;
		}
		HttpRequest request = HttpClientContext.adapt(context).getRequest();
		return request != null && isGet(request);
	}

	@Override
	public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
		return backoff(execCount);
	}

	@Override
	public TimeValue getRetryInterval(HttpRequest request, IOException exception, int execCount, HttpContext context) {
		return backoff(execCount);
	}

	private static boolean isGet(HttpRequest request) {
		return "GET".equalsIgnoreCase(request.getMethod());
	}

	private static TimeValue backoff(int execCount) {
		long ms = INITIAL_BACKOFF_MS * (1L << (execCount - 1));
		return TimeValue.ofMilliseconds(ms);
	}
}
