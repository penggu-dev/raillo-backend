package com.sudo.raillo.payment.adapter.integration.toss;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;
import com.sudo.raillo.payment.application.PaymentApprovalReconciler;
import com.sudo.raillo.payment.application.required.PaymentGateway;

/** 복구 Worker 전용 Toss 호출 설정. 사용자 경로와 커넥션 풀을 나눈다. */
@Configuration
public class TossWorkerClientConfig {

	@Bean(destroyMethod = "close")
	public PoolingHttpClientConnectionManager tossWorkerHttpConnectionManager(TossPaymentProperties properties) {
		return PoolingHttpClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.of(properties.connectTimeout()))
				.setTimeToLive(TimeValue.of(properties.keepAliveDuration()))
				.build())
			.setMaxConnPerRoute(properties.worker().maxConnectionsPerRoute())
			.setMaxConnTotal(properties.worker().maxTotal())
			.build();
	}

	@Bean(destroyMethod = "close")
	public CloseableHttpClient tossWorkerHttpClient(
		@Qualifier("tossWorkerHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
		TossPaymentProperties properties
	) {
		return HttpClients.custom()
			.setConnectionManager(connectionManager)
			.setDefaultRequestConfig(RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.of(properties.worker().connectionRequestTimeout()))
				.setResponseTimeout(Timeout.of(properties.worker().readTimeout()))
				.build())
			.setRetryStrategy(new TossGetRetryStrategy())
			.evictIdleConnections(TimeValue.of(properties.evictIdleAfter()))
			.evictExpiredConnections()
			.build();
	}

	@Bean
	public RestClient tossWorkerRestClient(
		@Qualifier("tossWorkerHttpClient") CloseableHttpClient tossWorkerHttpClient, TossPaymentProperties properties
	) {
		String encodedSecretKey = Base64.getEncoder()
			.encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

		return RestClient.builder()
			.requestFactory(new HttpComponentsClientHttpRequestFactory(tossWorkerHttpClient))
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	@Bean
	public TossPaymentClient tossWorkerPaymentClient(
		@Qualifier("tossWorkerRestClient") RestClient restClient, ObjectMapper objectMapper, TossApiMetrics metrics
	) {
		return new TossPaymentClient(restClient, objectMapper, metrics, "worker");
	}

	@Bean
	public PaymentGateway tossWorkerPaymentGateway(@Qualifier("tossWorkerPaymentClient") TossPaymentClient client) {
		return new TossPaymentGateway(client);
	}

	@Bean
	public PaymentApprovalReconciler workerApprovalReconciler(
		@Qualifier("tossWorkerPaymentGateway") PaymentGateway gateway
	) {
		return new PaymentApprovalReconciler(gateway);
	}
}
