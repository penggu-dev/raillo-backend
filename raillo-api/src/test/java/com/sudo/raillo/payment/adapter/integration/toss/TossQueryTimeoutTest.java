package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 같은 커넥션 풀 위에서 confirm과 query가 다른 응답 타임아웃을 쓰는지 본다. */
@DisplayName("연산별 read-timeout")
class TossQueryTimeoutTest {

	@Test
	@DisplayName("query는 confirm보다 먼저 끊기고 confirm은 같은 응답을 받아낸다")
	void queryTimesOutWhileConfirmSucceeds() throws Exception {
		try (StubHttpServer server = StubHttpServer.start(200, Duration.ofMillis(800));
			 CloseableHttpClient httpClient = client()) {

			HttpComponentsClientHttpRequestFactory queryFactory =
				new HttpComponentsClientHttpRequestFactory(httpClient);
			queryFactory.setReadTimeout(Duration.ofMillis(300));

			RestClient queryClient = TossHttpClients.restClientBuilder(server.baseUrl(), "test_sk")
				.requestFactory(queryFactory).build();
			RestClient confirmClient = TossHttpClients.restClientBuilder(server.baseUrl(), "test_sk")
				.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();

			assertThatThrownBy(() -> queryClient.get().uri("/v1/payments/k")
				.exchange((req, res) -> res.getStatusCode().value(), false))
				.as("query는 300ms에 끊긴다")
				.isInstanceOf(ResourceAccessException.class)
				.hasCauseInstanceOf(java.io.InterruptedIOException.class);

			int confirmStatus = confirmClient.post().uri("/v1/payments/k")
				.body("{}")
				.<Integer>exchange((req, res) -> res.getStatusCode().value(), false);
			assertThat(confirmStatus)
				.as("confirm은 클라이언트 기본값 2초를 쓰므로 800ms 응답을 받는다")
				.isEqualTo(200);
		}
	}

	private static CloseableHttpClient client() {
		PoolingHttpClientConnectionManager cm = TossHttpClients.connectionManager(
			"test", Duration.ofSeconds(1), Duration.ofSeconds(60), 5, 5);
		return TossHttpClients.httpClient("test", cm,
			Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30));
	}
}
