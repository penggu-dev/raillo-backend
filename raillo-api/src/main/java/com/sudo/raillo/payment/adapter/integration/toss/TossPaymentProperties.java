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
	@NotNull @DefaultValue("2s") Duration connectionRequestTimeout,
	@Positive @DefaultValue("100") int maxConnectionsPerRoute,
	@Positive @DefaultValue("200") int maxTotal,
	@NotNull @DefaultValue("60s") Duration keepAliveDuration,
	@NotNull @DefaultValue("30s") Duration evictIdleAfter
) {
	public TossPaymentProperties {
		if (secretKey != null && secretKey.startsWith("${")) {
			throw new IllegalStateException(
				"TOSS_SECRET_KEY 환경변수가 설정되지 않았습니다. .env 파일을 확인하세요."
			);
		}
	}
}
