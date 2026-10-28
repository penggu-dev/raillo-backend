package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.sudo.raillo.payment.application.recovery.PaymentRecoveryWorker;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.support.annotation.ServiceTest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@ServiceTest
@DisplayName("Worker 전용 Toss 클라이언트")
class TossWorkerClientConfigTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("복구 Worker는 Worker 전용 판정기를 주입받는다")
	void recoveryWorkerUsesWorkerReconciler() {
		// given
		PaymentRecoveryWorker worker = context.getBean(PaymentRecoveryWorker.class);

		// when
		Object injected = ReflectionTestUtils.getField(worker, "reconciler");

		// then
		assertThat(injected).isSameAs(context.getBean("workerApprovalReconciler"));
	}

	@Test
	@DisplayName("사용자 경로와 Worker 경로가 서로 다른 커넥션 풀과 게이트웨이를 쓴다")
	void workerBeansAreSeparateFromUserPath() {
		// given
		PaymentGateway userGateway = context.getBean(PaymentGateway.class);
		PaymentGateway workerGateway = (PaymentGateway)context.getBean("tossWorkerPaymentGateway");

		// when
		PoolingHttpClientConnectionManager userPool =
			(PoolingHttpClientConnectionManager)context.getBean("tossHttpConnectionManager");
		PoolingHttpClientConnectionManager workerPool =
			(PoolingHttpClientConnectionManager)context.getBean("tossWorkerHttpConnectionManager");

		// then
		assertThat(workerGateway).isNotSameAs(userGateway);
		assertThat(workerPool).isNotSameAs(userPool);
	}

	@Test
	@DisplayName("Worker 풀은 Worker 전용 상한을 받는다")
	void workerPoolUsesWorkerLimits() {
		// given
		PoolingHttpClientConnectionManager userPool =
			context.getBean("tossHttpConnectionManager", PoolingHttpClientConnectionManager.class);
		PoolingHttpClientConnectionManager workerPool =
			context.getBean("tossWorkerHttpConnectionManager", PoolingHttpClientConnectionManager.class);
		TossPaymentProperties properties = context.getBean(TossPaymentProperties.class);

		// when & then
		assertThat(workerPool.getMaxTotal())
			.as("Worker 풀이 사용자 풀 크기를 받으면 추출이 접근자를 뒤섞은 것이다")
			.isEqualTo(properties.worker().maxTotal());
		assertThat(workerPool.getDefaultMaxPerRoute()).isEqualTo(properties.worker().maxConnectionsPerRoute());
		assertThat(userPool.getMaxTotal()).isEqualTo(properties.maxTotal());
		assertThat(userPool.getDefaultMaxPerRoute()).isEqualTo(properties.maxConnectionsPerRoute());
	}

	/**
	 * 두 클라이언트의 {@code queryBreaker} 필드 타입이 같아 인자를 바꿔 넘겨도 컴파일과 다른 테스트는 통과한다.
	 * 실제 배선이 맞는지는 이름으로 확인해야 한다.
	 */
	@Test
	@DisplayName("사용자와 Worker 경로가 각자의 조회 브레이커로 배선된다")
	void queryBreakersAreWiredToTheirOwnPath() {
		// given
		TossPaymentClient userClient = context.getBean("tossPaymentClient", TossPaymentClient.class);
		TossPaymentClient workerClient = context.getBean("tossWorkerPaymentClient", TossPaymentClient.class);

		// when
		CircuitBreaker userQueryBreaker = (CircuitBreaker)ReflectionTestUtils.getField(userClient, "queryBreaker");
		CircuitBreaker workerQueryBreaker = (CircuitBreaker)ReflectionTestUtils.getField(workerClient, "queryBreaker");

		// then
		assertThat(userQueryBreaker.getName()).isEqualTo("toss-query-user");
		assertThat(workerQueryBreaker.getName()).isEqualTo("toss-query-worker");
	}
}
