package com.sudo.raillo.payment.adapter.integration.toss;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/** 사용자 경로와 Worker 경로가 공유하는 Toss HTTP 스택 조립. 값만 다르고 구조는 같다. */
@Slf4j
final class TossHttpClients {

	private TossHttpClients() {
	}

	static PoolingHttpClientConnectionManager connectionManager(
		String poolName, Duration connectTimeout, Duration keepAlive, int maxPerRoute, int maxTotal
	) {
		log.info("[TOSS] 커넥션 풀 초기화: pool={}, connectTimeout={}, keepAlive={}, maxPerRoute={}, maxTotal={}",
			poolName, connectTimeout, keepAlive, maxPerRoute, maxTotal);

		return PoolingHttpClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.of(connectTimeout))
				.setTimeToLive(TimeValue.of(keepAlive))
				.build())
			.setMaxConnPerRoute(maxPerRoute)
			.setMaxConnTotal(maxTotal)
			.build();
	}

	static CloseableHttpClient httpClient(
		String poolName,
		PoolingHttpClientConnectionManager connectionManager,
		Duration connectionRequestTimeout,
		Duration responseTimeout,
		Duration evictIdleAfter
	) {
		log.info("[TOSS] HTTP 클라이언트 초기화: pool={}, connectionRequestTimeout={}, responseTimeout={}, evictIdle={}",
			poolName, connectionRequestTimeout, responseTimeout, evictIdleAfter);

		return HttpClients.custom()
			.setConnectionManager(connectionManager)
			.setDefaultRequestConfig(RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.of(connectionRequestTimeout))
				.setResponseTimeout(Timeout.of(responseTimeout))
				.build())
			.setRetryStrategy(new TossGetRetryStrategy())
			.evictIdleConnections(TimeValue.of(evictIdleAfter))
			.evictExpiredConnections()
			.build();
	}

	static RestClient.Builder restClientBuilder(String baseUrl, String secretKey) {
		String encoded = Base64.getEncoder()
			.encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
	}
}
