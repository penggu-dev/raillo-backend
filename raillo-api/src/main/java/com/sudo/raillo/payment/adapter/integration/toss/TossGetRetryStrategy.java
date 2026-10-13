package com.sudo.raillo.payment.adapter.integration.toss;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * Toss API 호출용 GET 전용 재시도 전략.
 *
 * <p><strong>GET만 재시도하는 이유</strong>: 쓰기 호출을 전송 층에서 다시 보내도 안전하다는 보장이 아직 없다. Toss는 POST에
 * {@code Idempotency-Key}를 지원하지만(15일 dedup) confirm은 헤더를 보내지 않고(#260) cancel은 호출마다 새 키를 만든다.
 * 게이트웨이가 흔들릴 때 실패한 쓰기를 즉시 다시 보내면 부하를 보태기도 한다. POST 실패는 IN_PROGRESS attempt를 남겨
 * 사용자 재시도 경로나 Recovery Worker(#270)가 더 느린 주기로 처리한다. GET(query)은 서버 상태를 바꾸지 않아 자동 재시도가 안전.</p>
 *
 * <p><strong>재시도 조건</strong>:</p>
 * <ul>
 *   <li>I/O 예외 (SocketTimeoutException, Connection reset 등) — 응답 유실 or 네트워크 순간 장애</li>
 *   <li>5xx 응답 (500, 502, 503, 504 등) — Toss 서버 일시 오류</li>
 *   <li>4xx는 재시도 대상 아님 — 요청 자체 문제라 재시도해도 결과 동일</li>
 * </ul>
 *
 * <p><strong>backoff</strong>: 200ms → 400ms를 중심으로 ±50% 지터(1차 100~300ms, 2차 200~600ms).
 * 최대 2회 재시도(총 3회 호출). 기대 총 대기 600ms는 지터 전과 같다. 길이 조정은 #305에서 다룬다.</p>
 */
public class TossGetRetryStrategy implements HttpRequestRetryStrategy {

	private static final int MAX_RETRIES = 2;
	private static final long BACKOFF_BASE_MS = 200L;

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
		long base = BACKOFF_BASE_MS * (1L << (execCount - 1));
		// execCount가 커지면 시프트가 넘쳐 0이 된다. retryRequest가 2회로 막으므로 현재 경로에서는 오지 않지만,
		// 0이면 nextLong이 예외를 던져 산술 오류가 결제 결과 불명으로 위장된다.
		if (base <= 0) {
			return TimeValue.ofMilliseconds(BACKOFF_BASE_MS);
		}
		return TimeValue.ofMilliseconds(base / 2 + ThreadLocalRandom.current().nextLong(base));
	}
}
