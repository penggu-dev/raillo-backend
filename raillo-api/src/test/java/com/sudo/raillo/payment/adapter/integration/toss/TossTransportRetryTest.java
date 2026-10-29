package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 전송 계층(Apache HttpClient)의 재시도 동작을 실제 HTTP 서버로 검증한다. */
@DisplayName("Toss 전송 계층 재시도")
class TossTransportRetryTest {

	private static RestClient restClient(String baseUrl, CloseableHttpClient httpClient) {
		return TossHttpClients.restClientBuilder(baseUrl, "test_sk")
			.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
			.build();
	}

	private static CloseableHttpClient httpClient() {
		PoolingHttpClientConnectionManager cm = TossHttpClients.connectionManager(
			"test", Duration.ofSeconds(1), Duration.ofSeconds(60), 5, 5);
		return TossHttpClients.httpClient("test", cm,
			Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30));
	}

	private static int exchange(RestClient client, String method, String path) {
		return switch (method) {
			case "GET" -> client.get().uri(path).exchange((req, res) -> res.getStatusCode().value(), false);
			case "POST" -> client.post().uri(path).body("{}").exchange((req, res) -> res.getStatusCode().value(), false);
			default -> throw new IllegalArgumentException(method);
		};
	}

	@Test
	@DisplayName("POST는 503에 재시도하지 않는다")
	void post_serverError_isNotRetried() throws Exception {
		try (StubHttpServer server = StubHttpServer.start(503);
			 CloseableHttpClient httpClient = httpClient()) {

			exchange(restClient(server.baseUrl(), httpClient), "POST", "/v1/payments/confirm");

			assertThat(server.requestCount())
				.as("승인 요청은 멱등하지 않아 전송 계층이 다시 보내면 이중 청구가 된다")
				.isEqualTo(1);
		}
	}

	@Test
	@DisplayName("POST는 429에 재시도하지 않는다")
	void post_tooManyRequests_isNotRetried() throws Exception {
		try (StubHttpServer server = StubHttpServer.start(429);
			 CloseableHttpClient httpClient = httpClient()) {

			exchange(restClient(server.baseUrl(), httpClient), "POST", "/v1/payments/confirm");

			assertThat(server.requestCount())
				.as("승인 요청은 멱등하지 않아 전송 계층이 다시 보내면 이중 청구가 된다")
				.isEqualTo(1);
		}
	}

	@Test
	@DisplayName("GET도 전송 계층에서는 재시도하지 않는다")
	void get_serverError_isNotRetriedByTransport() throws Exception {
		try (StubHttpServer server = StubHttpServer.start(503);
			 CloseableHttpClient httpClient = httpClient()) {

			exchange(restClient(server.baseUrl(), httpClient), "GET", "/v1/payments/key");

			assertThat(server.requestCount())
				.as("재시도는 TossPaymentClient의 애플리케이션 계층 루프 한 곳에만 있다")
				.isEqualTo(1);
		}
	}
}
