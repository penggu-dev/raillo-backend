package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TossGetRetryStrategyTest {

	private final TossGetRetryStrategy strategy = new TossGetRetryStrategy();

	@Nested
	@DisplayName("I/O 예외 재시도")
	class IoException {

		@Test
		@DisplayName("GET에서 SocketTimeoutException 발생 시 재시도한다")
		void get_socketTimeout_retries() {
			HttpRequest getRequest = new BasicHttpRequest("GET", "/v1/payments/x");
			IOException ex = new SocketTimeoutException("read timed out");

			assertThat(strategy.retryRequest(getRequest, ex, 1, HttpClientContext.create())).isTrue();
			assertThat(strategy.retryRequest(getRequest, ex, 2, HttpClientContext.create())).isTrue();
			assertThat(strategy.retryRequest(getRequest, ex, 3, HttpClientContext.create()))
				.as("최대 2회 재시도 이후에는 중단").isFalse();
		}

		@Test
		@DisplayName("POST에서 I/O 예외 발생해도 재시도하지 않는다")
		void post_ioException_doesNotRetry() {
			HttpRequest postRequest = new BasicHttpRequest("POST", "/v1/payments/confirm");
			IOException ex = new SocketTimeoutException("read timed out");

			assertThat(strategy.retryRequest(postRequest, ex, 1, HttpClientContext.create()))
				.as("쓰기 호출은 재시도 안전성이 보장되지 않아 전송 층에서 재시도 금지").isFalse();
		}
	}

	@Nested
	@DisplayName("HTTP 상태 코드 재시도")
	class StatusCode {

		@Test
		@DisplayName("GET 5xx 응답 시 재시도한다")
		void get_5xx_retries() {
			HttpClientContext ctx = HttpClientContext.create();
			ctx.setRequest(new BasicHttpRequest("GET", "/v1/payments/x"));
			BasicHttpResponse response = new BasicHttpResponse(503, "Service Unavailable");

			assertThat(strategy.retryRequest(response, 1, ctx)).isTrue();
			assertThat(strategy.retryRequest(response, 2, ctx)).isTrue();
			assertThat(strategy.retryRequest(response, 3, ctx))
				.as("최대 2회 재시도 이후에는 중단").isFalse();
		}

		@Test
		@DisplayName("GET 4xx 응답은 재시도하지 않는다")
		void get_4xx_doesNotRetry() {
			HttpClientContext ctx = HttpClientContext.create();
			ctx.setRequest(new BasicHttpRequest("GET", "/v1/payments/x"));
			BasicHttpResponse response = new BasicHttpResponse(404, "Not Found");

			assertThat(strategy.retryRequest(response, 1, ctx))
				.as("4xx는 요청 자체 문제라 재시도 무의미").isFalse();
		}

		@Test
		@DisplayName("POST 5xx 응답은 재시도하지 않는다")
		void post_5xx_doesNotRetry() {
			HttpClientContext ctx = HttpClientContext.create();
			ctx.setRequest(new BasicHttpRequest("POST", "/v1/payments/confirm"));
			BasicHttpResponse response = new BasicHttpResponse(502, "Bad Gateway");

			assertThat(strategy.retryRequest(response, 1, ctx))
				.as("POST 5xx는 IN_PROGRESS attempt로 남겨 회복 경로에 위임").isFalse();
		}
	}

	@Nested
	@DisplayName("지수 backoff와 지터")
	class Backoff {

		private static final int SAMPLES = 20;

		private List<Long> intervalsOf(int execCount) {
			HttpRequest req = new BasicHttpRequest("GET", "/x");
			IOException ex = new SocketTimeoutException();
			List<Long> intervals = new ArrayList<>();
			for (int i = 0; i < SAMPLES; i++) {
				intervals.add(strategy.getRetryInterval(req, ex, execCount, HttpClientContext.create()).toMilliseconds());
			}
			return intervals;
		}

		@Test
		@DisplayName("1차 재시도 간격을 반복해 구하면 모두 100ms 이상 300ms 미만이다")
		void firstRetry_staysWithinBand() {
			// given
			// when
			List<Long> intervals = intervalsOf(1);

			// then
			assertThat(intervals).allSatisfy(ms ->
				assertThat(ms).isGreaterThanOrEqualTo(100).isLessThan(300));
		}

		@Test
		@DisplayName("2차 재시도 간격을 반복해 구하면 모두 200ms 이상 600ms 미만이다")
		void secondRetry_staysWithinBand() {
			// given
			// when
			List<Long> intervals = intervalsOf(2);

			// then
			assertThat(intervals).allSatisfy(ms ->
				assertThat(ms).isGreaterThanOrEqualTo(200).isLessThan(600));
		}

		@Test
		@DisplayName("같은 실행 횟수로 여러 번 호출하면 간격이 한 값으로 고정되지 않는다")
		void sameExecCount_producesVaryingIntervals() {
			// given
			// when
			List<Long> intervals = intervalsOf(2);

			// then
			assertThat(new HashSet<>(intervals))
				.as("지터가 없으면 값이 하나뿐이라 장애 시 재시도가 같은 순간에 몰린다")
				.hasSizeGreaterThan(1);
		}
	}
}
