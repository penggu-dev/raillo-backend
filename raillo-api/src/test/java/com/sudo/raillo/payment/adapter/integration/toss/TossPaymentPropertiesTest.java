package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TossPaymentPropertiesTest {

	@Test
	@DisplayName("사용자 경로의 query-read-timeout이 read-timeout 이상이면 설정을 거절한다")
	void create_queryReadTimeoutNotShorterThanReadTimeout_isRejected() {
		// given & when & then
		assertThatThrownBy(() -> create(Duration.ofSeconds(15), Duration.ofSeconds(15)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payment.toss.api.query-read-timeout");
	}

	@Test
	@DisplayName("사용자 경로의 query-read-timeout이 read-timeout보다 길면 설정을 거절한다")
	void create_queryReadTimeoutLongerThanReadTimeout_isRejected() {
		// given & when & then
		assertThatThrownBy(() -> create(Duration.ofSeconds(15), Duration.ofSeconds(20)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payment.toss.api.query-read-timeout");
	}

	@Test
	@DisplayName("사용자 경로의 query-read-timeout이 read-timeout보다 짧으면 받아들인다")
	void create_queryReadTimeoutShorterThanReadTimeout_isAccepted() {
		// given & when
		TossPaymentProperties properties = create(Duration.ofSeconds(15), Duration.ofSeconds(5));

		// then
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
		assertThat(properties.queryReadTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("Worker 경로의 query-read-timeout이 read-timeout 이상이면 설정을 거절한다")
	void createWorkerHttp_queryReadTimeoutNotShorterThanReadTimeout_isRejected() {
		// given & when & then
		assertThatThrownBy(() -> new TossPaymentProperties.WorkerHttp(
			Duration.ofSeconds(20), Duration.ofSeconds(20),
			Duration.ofSeconds(5), Duration.ofSeconds(20), 10, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payment.toss.api.worker.query-read-timeout");
	}

	@Test
	@DisplayName("Worker 경로의 query-read-timeout이 read-timeout보다 짧으면 받아들인다")
	void createWorkerHttp_queryReadTimeoutShorterThanReadTimeout_isAccepted() {
		// given & when
		TossPaymentProperties.WorkerHttp worker = new TossPaymentProperties.WorkerHttp(
			Duration.ofSeconds(20), Duration.ofSeconds(10),
			Duration.ofSeconds(5), Duration.ofSeconds(20), 10, 10);

		// then
		assertThat(worker.readTimeout()).isEqualTo(Duration.ofSeconds(20));
		assertThat(worker.queryReadTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	private TossPaymentProperties create(Duration readTimeout, Duration queryReadTimeout) {
		return new TossPaymentProperties(
			"https://api.tosspayments.com", "client_key", "test_sk_live",
			"2022-11-16",
			Duration.ofSeconds(3), readTimeout, queryReadTimeout, Duration.ofSeconds(2),
			Duration.ofSeconds(10), 3, Duration.ofMillis(200),
			100, 200,
			Duration.ofSeconds(60), Duration.ofSeconds(30),
			new TossPaymentProperties.WorkerHttp(
				Duration.ofSeconds(20), Duration.ofSeconds(10),
				Duration.ofSeconds(5), Duration.ofSeconds(20), 10, 10));
	}
}
