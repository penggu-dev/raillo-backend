package com.sudo.raillo.payment.adapter.integration.toss;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "payment.toss.api")
public record TossPaymentProperties(
	@NotBlank String baseUrl,
	@NotBlank String clientKey,
	@NotBlank String secretKey,
	String version,
	@NotNull @DefaultValue("3s") Duration connectTimeout,
	@NotNull @DefaultValue("15s") Duration readTimeout,
	@NotNull @DefaultValue("5s") Duration queryReadTimeout,
	@NotNull @DefaultValue("2s") Duration connectionRequestTimeout,
	@NotNull @DefaultValue("10s") Duration queryBudget,
	@Positive @DefaultValue("3") int queryMaxAttempts,
	@NotNull @DefaultValue("200ms") Duration queryBackoffBase,
	@Positive @DefaultValue("100") int maxConnectionsPerRoute,
	@Positive @DefaultValue("200") int maxTotal,
	@NotNull @DefaultValue("60s") Duration keepAliveDuration,
	@NotNull @DefaultValue("30s") Duration evictIdleAfter,
	@NotNull @DefaultValue WorkerHttp worker
) {
	/** 복구 Worker 전용 Toss 호출 예산. */
	public record WorkerHttp(
		@NotNull @DefaultValue("20s") Duration readTimeout,
		@NotNull @DefaultValue("10s") Duration queryReadTimeout,
		@NotNull @DefaultValue("5s") Duration connectionRequestTimeout,
		@NotNull @DefaultValue("20s") Duration queryBudget,
		@Positive @DefaultValue("10") int maxConnectionsPerRoute,
		@Positive @DefaultValue("10") int maxTotal
	) {
		public WorkerHttp {
			requireQueryNotSlowerThanConfirm("payment.toss.api.worker", queryReadTimeout, readTimeout);
		}
	}

	public TossPaymentProperties {
		if (secretKey != null && secretKey.startsWith("${")) {
			throw new IllegalStateException(
				"TOSS_SECRET_KEY 환경변수가 설정되지 않았습니다. .env 파일을 확인하세요."
			);
		}
		requireQueryNotSlowerThanConfirm("payment.toss.api", queryReadTimeout, readTimeout);
	}

	/**
	 * 조회는 멱등이라 짧게 끊어도 다시 조회하면 되므로 confirm보다 길면 설계 전제가 깨진다
	 * (docs/payment/README.md "재시도는 전송 계층이 아니라 조회 한 곳에서만 한다").
	 */
	private static void requireQueryNotSlowerThanConfirm(String prefix, Duration queryReadTimeout, Duration readTimeout) {
		if (queryReadTimeout != null && readTimeout != null && queryReadTimeout.compareTo(readTimeout) >= 0) {
			throw new IllegalArgumentException(
				"%s.query-read-timeout(%s)는 read-timeout(%s)보다 짧아야 한다".formatted(prefix, queryReadTimeout, readTimeout));
		}
	}
}
