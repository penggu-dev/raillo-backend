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

/**
 * Toss Payments HTTP 클라이언트 인프라 설정.
 *
 * <p>Apache HttpClient 5 기반으로 커넥션 풀, 타임아웃, 백그라운드 청소를 구성한다. 세 Bean이 아래처럼 체인으로 연결된다.</p>
 *
 * <pre>
 * RestClient (앱 코드가 사용)
 *   └─ HttpComponentsClientHttpRequestFactory (Spring ↔ Apache 어댑터)
 *        └─ CloseableHttpClient (Apache 실행자)
 *             └─ PoolingHttpClientConnectionManager (커넥션 풀)
 *                  └─ TCP 커넥션 다수 ─── Toss 서버
 * </pre>
 *
 * <p>결제 요청이 흐르는 순서:</p>
 * <ol>
 *   <li>TossPaymentClient가 RestClient로 요청 시작 (예: POST /v1/payments/confirm)</li>
 *   <li>RestClient → HttpComponentsClientHttpRequestFactory에 실행 위임</li>
 *   <li>Factory → CloseableHttpClient(tossHttpClient) 호출</li>
 *   <li>HttpClient → ConnectionManager에서 커넥션 획득 시도
 *     <ul>
 *       <li>풀에 idle 커넥션 있음 → 즉시 반환 (재사용, TCP handshake 생략)</li>
 *       <li>없고 풀 여유 있음 → 새 TCP 연결 수립 (최대 connectTimeout 3초)</li>
 *       <li>없고 풀 가득 → 큐 대기 (최대 connectionRequestTimeout 2초)</li>
 *     </ul>
 *   </li>
 *   <li>획득한 커넥션으로 요청 데이터 송신</li>
 *   <li>응답 대기 (최대 responseTimeout 15초)</li>
 *   <li>응답 수신 → 커넥션은 풀로 반납 (idle 상태 진입)</li>
 *   <li>백그라운드 데몬 스레드가 주기적으로 풀 순회
 *     <ul>
 *       <li>idle 시간이 evictIdleAfter(30초) 초과 → close</li>
 *       <li>생성 후 keepAliveDuration(60초) 초과 → close</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentClientConfig {

	/**
	 * 커넥션 풀 저장소. TCP 커넥션들을 담아두고 재사용한다.
	 *
	 * <ul>
	 *   <li>{@code connectTimeout}: 새 커넥션 TCP handshake 최대 대기</li>
	 *   <li>{@code keepAliveDuration}: 커넥션 하나의 총 수명 (생성 시점부터 카운트, idle 여부 무관)</li>
	 *   <li>{@code maxConnectionsPerRoute}: (scheme, host, port) 조합당 상한</li>
	 *   <li>{@code maxTotal}: 풀 전체 상한</li>
	 * </ul>
	 *
	 * <p>{@code destroyMethod = "close"}: JVM 종료 시 풀 안 커넥션을 명시적으로 닫아 소켓 leak을 방지한다. {@link java.io.Closeable} 구현이라 Spring이 자동 감지도 하지만 의도를 드러내려고 명시.</p>
	 */
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

	/**
	 * 실제 HTTP 실행자. 위 풀에서 커넥션을 빌려 요청, 응답을 수행하고, 요청 레벨 타임아웃과 백그라운드 청소 정책을 설정한다.
	 *
	 * <ul>
	 *   <li>{@code connectionRequestTimeout}: 풀에서 커넥션 획득 대기 상한</li>
	 *   <li>{@code responseTimeout}: 요청 송신 후 응답 대기 상한 (기존 readTimeout 개념)</li>
	 *   <li>{@code evictIdleConnections}: 백그라운드 데몬 스레드가 idle {@code evictIdleAfter} 초과 커넥션을 close. 서버가 먼저 끊기 전에 클라이언트가 폐기해 stale 방지.</li>
	 *   <li>{@code evictExpiredConnections}: TTL({@code keepAliveDuration}) 초과 커넥션을 close</li>
	 * </ul>
	 *
	 * <p>백그라운드 청소 스레드는 이 Bean이 만들어지는 순간(Spring 컨텍스트 로드) 데몬 스레드로 시작되고, JVM 종료 또는 {@code close()} 호출까지 계속 실행된다.</p>
	 */
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
			.setRetryStrategy(new TossGetRetryStrategy())
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
