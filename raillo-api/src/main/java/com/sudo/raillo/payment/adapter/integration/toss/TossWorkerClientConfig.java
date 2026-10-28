package com.sudo.raillo.payment.adapter.integration.toss;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
		return TossHttpClients.connectionManager("worker",
			properties.connectTimeout(), properties.keepAliveDuration(),
			properties.worker().maxConnectionsPerRoute(), properties.worker().maxTotal());
	}

	@Bean(destroyMethod = "close")
	public CloseableHttpClient tossWorkerHttpClient(
		@Qualifier("tossWorkerHttpConnectionManager") PoolingHttpClientConnectionManager connectionManager,
		TossPaymentProperties properties
	) {
		return TossHttpClients.httpClient("worker", connectionManager,
			properties.worker().connectionRequestTimeout(), properties.worker().readTimeout(),
			properties.evictIdleAfter());
	}

	@Bean
	public RestClient tossWorkerRestClient(
		@Qualifier("tossWorkerHttpClient") CloseableHttpClient tossWorkerHttpClient, TossPaymentProperties properties
	) {
		return TossHttpClients.restClientBuilder(properties.baseUrl(), properties.secretKey())
			.requestFactory(new HttpComponentsClientHttpRequestFactory(tossWorkerHttpClient))
			.build();
	}

	@Bean
	public TossPaymentClient tossWorkerPaymentClient(
		@Qualifier("tossWorkerRestClient") RestClient restClient, ObjectMapper objectMapper, TossApiMetrics metrics,
		TossPaymentProperties properties
	) {
		return new TossPaymentClient(restClient, objectMapper, metrics, "worker",
			new QueryRetryPolicy(properties.queryMaxAttempts(), properties.worker().queryBudget(), properties.queryBackoffBase()));
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
