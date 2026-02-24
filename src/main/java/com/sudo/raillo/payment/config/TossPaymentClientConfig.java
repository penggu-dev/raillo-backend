package com.sudo.raillo.payment.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

		// 명시적으로 팩토리를 선언: 부팅 로그에서 어떤 HTTP 클라이언트가 선택됐는지 박제
		// (과거에 application/octet-stream 버그 발생 시 이 정보가 없어 원인 추적 불가했음)
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		log.info("[TOSS] RestClient HTTP 클라이언트: {}", requestFactory.getClass().getSimpleName());

		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}
}
