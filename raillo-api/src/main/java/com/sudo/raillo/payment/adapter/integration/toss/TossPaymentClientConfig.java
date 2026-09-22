package com.sudo.raillo.payment.adapter.integration.toss;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentClientConfig {

	@Bean(destroyMethod = "close")
	public PoolingHttpClientConnectionManager tossHttpConnectionManager(TossPaymentProperties properties) {
		return PoolingHttpClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.of(properties.connectTimeout()))
				.setTimeToLive(TimeValue.of(properties.keepAliveDuration()))
				.build())
			.setMaxConnPerRoute(properties.maxConnectionsPerRoute())
			.setMaxConnTotal(properties.maxTotal())
			.build();
	}

	@Bean(destroyMethod = "close")
	public CloseableHttpClient tossHttpClient(
		PoolingHttpClientConnectionManager connectionManager,
		TossPaymentProperties properties
	) {
		log.info("[TOSS] HTTP 클라이언트 초기화: connectTimeout={}, readTimeout={}, connectionRequestTimeout={}, "
				+ "maxPerRoute={}, maxTotal={}, keepAlive={}, evictIdle={}",
			properties.connectTimeout(), properties.readTimeout(), properties.connectionRequestTimeout(),
			properties.maxConnectionsPerRoute(), properties.maxTotal(),
			properties.keepAliveDuration(), properties.evictIdleAfter());

		return HttpClients.custom()
			.setConnectionManager(connectionManager)
			.setDefaultRequestConfig(RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.of(properties.connectionRequestTimeout()))
				.setResponseTimeout(Timeout.of(properties.readTimeout()))
				.build())
			.evictIdleConnections(TimeValue.of(properties.evictIdleAfter()))
			.evictExpiredConnections()
			.build();
	}

	@Bean
	public RestClient tossPaymentRestClient(CloseableHttpClient tossHttpClient, TossPaymentProperties properties) {
		String encodedSecretKey = Base64.getEncoder()
			.encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

		HttpComponentsClientHttpRequestFactory requestFactory =
			new HttpComponentsClientHttpRequestFactory(tossHttpClient);

		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.requestInterceptor((request, body, execution) -> {
				log.debug("[TOSS] → {} {}", request.getMethod(), request.getURI());
				log.debug("[TOSS] → headers: {}", maskSensitiveHeaders(request.getHeaders()));
				log.debug("[TOSS] → body: {}", new String(body, StandardCharsets.UTF_8));
				return execution.execute(request, body);
			})
			.build();
	}

	private Map<String, List<String>> maskSensitiveHeaders(HttpHeaders headers) {
		Map<String, List<String>> masked = new LinkedHashMap<>();
		headers.forEach((name, values) -> masked.put(name, List.copyOf(values)));
		if (masked.containsKey(HttpHeaders.AUTHORIZATION)) {
			masked.put(HttpHeaders.AUTHORIZATION, List.of("Basic ***"));
		}
		return masked;
	}
}
