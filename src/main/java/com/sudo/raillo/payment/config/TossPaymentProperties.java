package com.sudo.raillo.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.toss.api")
public record TossPaymentProperties(
	String baseUrl,
	String clientKey,
	String secretKey,
	String version
) {
}
