package com.sudo.raillo.payment.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentClientConfig {

	@Bean
	public RestClient tossPaymentRestClient(TossPaymentProperties properties) {
		String encodedSecretKey = Base64.getEncoder()
			.encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.requestInterceptor((request, body, execution) -> {
				log.info("[TOSS] → 요청 URI: {} {}", request.getMethod(), request.getURI());
				log.info("[TOSS] → 요청 헤더: {}", request.getHeaders());
				log.info("[TOSS] → 요청 body: {}", new String(body, StandardCharsets.UTF_8));
				return execution.execute(request, body);
			})
			.build();
	}
}
