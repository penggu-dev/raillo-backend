package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;

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
				.as("POST는 idempotent하지 않아 자동 재시도 금지").isFalse();
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
				.as("POST 5xx는 IN_PROGRESS attempt 남기고 Recovery Worker에 위임").isFalse();
		}
	}

	@Nested
	@DisplayName("지수 backoff")
	class Backoff {

		@Test
		@DisplayName("실행 횟수에 따라 200ms → 400ms로 backoff가 증가한다")
		void backoff_doublesEachRetry() {
			HttpRequest req = new BasicHttpRequest("GET", "/x");
			IOException ex = new SocketTimeoutException();

			assertThat(strategy.getRetryInterval(req, ex, 1, HttpClientContext.create()).toMilliseconds())
				.isEqualTo(200);
			assertThat(strategy.getRetryInterval(req, ex, 2, HttpClientContext.create()).toMilliseconds())
				.isEqualTo(400);
		}
	}
}
