package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@RestClientTest
@Import(TossCircuitBreakerTest.TestConfig.class)
@DisplayName("Toss 서킷 브레이커")
class TossCircuitBreakerTest {

	static final String BASE_URL = "https://api.tosspayments.com";
	static final String QUERY_URL = BASE_URL + "/v1/payments/pk_1";
	static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";
	static final String ERROR_BODY = "{\"code\":\"X\",\"message\":\"x\"}";
	static final String QUERY_BODY =
		"{\"paymentKey\":\"pk_1\",\"orderId\":\"ORD-1\",\"totalAmount\":1000,"
			+ "\"status\":\"DONE\",\"method\":\"카드\"}";

	static class TestConfig {
		@Bean
		RestClient tossPaymentRestClient(RestClient.Builder builder) {
			String encoded = Base64.getEncoder()
				.encodeToString("test_sk:".getBytes(StandardCharsets.UTF_8));
			return builder.baseUrl(BASE_URL)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
				.build();
		}
	}

	@Autowired
	private RestClient restClient;
	@Autowired
	private MockRestServiceServer mockServer;

	private ObjectMapper objectMapper;
	private TossApiMetrics metrics;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		metrics = new TossApiMetrics(new SimpleMeterRegistry(),
			PoolingHttpClientConnectionManagerBuilder.create().build());
	}

	/** 조회와 승인에 같은 브레이커를 거는 클라이언트. 재시도 백오프는 짧게 둬서 테스트가 빨리 끝난다. */
	private TossPaymentClient clientWith(CircuitBreaker breaker) {
		return new TossPaymentClient(restClient, restClient, objectMapper, metrics, "user",
			new QueryRetryPolicy(3, Duration.ofSeconds(5), Duration.ofMillis(10)), breaker, breaker);
	}

	private static CircuitBreaker breakerWithMinimumCalls(int minimumCalls) {
		return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
			.slidingWindowType(SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(20)
			.minimumNumberOfCalls(minimumCalls)
			.failureRateThreshold(50f)
			.ignoreException(new DefinitiveFailurePredicate())
			.build());
	}

	private static CircuitBreaker halfOpenReadyBreaker() {
		return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
			.slidingWindowType(SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(10)
			.minimumNumberOfCalls(2)
			.permittedNumberOfCallsInHalfOpenState(2)
			.failureRateThreshold(50f)
			.ignoreException(new DefinitiveFailurePredicate())
			.build());
	}

	private static PaymentConfirmCommand command() {
		return new PaymentConfirmCommand("pk_1", "ORD-1", BigDecimal.valueOf(1000));
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	@DisplayName("회로가 열려 있으면 조회 루프가 돌지 않는다")
	void openCircuit_doesNotEnterRetryLoop() {
		CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");
		breaker.transitionToOpenState();
		TossPaymentClient client = clientWith(breaker);

		assertThatThrownBy(() -> client.queryPayment("pk_1"))
			.isInstanceOf(TossPaymentException.class)
			.extracting("errorCode").isEqualTo("QUERY_UNCERTAIN_CIRCUIT_OPEN");

		mockServer.verify();   // expect를 걸지 않아 예상 밖 요청이 오면 MockRestServiceServer가 AssertionError를 던진다
	}

	@Test
	@DisplayName("5xx가 임계값을 넘으면 회로가 열린다")
	void serverErrors_openTheCircuit() {
		CircuitBreaker breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
			.slidingWindowType(SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(10)
			.minimumNumberOfCalls(5)
			.failureRateThreshold(50f)
			.ignoreException(new DefinitiveFailurePredicate())
			.build());
		TossPaymentClient client = clientWith(breaker);

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(QUERY_URL))
			.andRespond(withServerError().body(ERROR_BODY).contentType(MediaType.APPLICATION_JSON));

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> client.queryPayment("pk_1")).isInstanceOf(TossPaymentException.class);
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	@DisplayName("404가 반복돼도 회로가 열리지 않는다")
	void notFound_doesNotOpenTheCircuit() {
		CircuitBreaker breaker = breakerWithMinimumCalls(5);
		TossPaymentClient client = clientWith(breaker);

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(QUERY_URL))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).body(ERROR_BODY).contentType(MediaType.APPLICATION_JSON));

		for (int i = 0; i < 20; i++) {
			assertThatThrownBy(() -> client.queryPayment("pk_1")).isInstanceOf(TossPaymentException.class);
		}

		assertThat(breaker.getState())
			.as("조회 404는 결제 없음 확정이라 게이트웨이 건강과 무관하다")
			.isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.getMetrics().getNumberOfBufferedCalls())
			.as("창에 아예 기록되지 않는다")
			.isZero();
	}

	@Test
	@DisplayName("400이 반복돼도 승인 회로가 열리지 않는다")
	void cardDeclines_doNotOpenTheCircuit() {
		CircuitBreaker breaker = breakerWithMinimumCalls(5);
		TossPaymentClient client = clientWith(breaker);

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ERROR_BODY).contentType(MediaType.APPLICATION_JSON));

		for (int i = 0; i < 20; i++) {
			assertThatThrownBy(() -> client.confirmPayment(command())).isInstanceOf(TossPaymentException.class);
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	@DisplayName("느린 성공이 임계값을 넘으면 회로가 열린다")
	void slowCalls_openTheCircuit() {
		CircuitBreaker breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
			.slidingWindowType(SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(10)
			.minimumNumberOfCalls(5)
			.slowCallRateThreshold(50f)
			.slowCallDurationThreshold(Duration.ofMillis(50))
			.ignoreException(new DefinitiveFailurePredicate())
			.build());
		TossPaymentClient client = clientWith(breaker);

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(QUERY_URL))
			.andRespond(request -> {
				sleepQuietly(80);
				return withSuccess(QUERY_BODY, MediaType.APPLICATION_JSON).createResponse(request);
			});

		for (int i = 0; i < 5; i++) {
			client.queryPayment("pk_1");
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	@DisplayName("half-open에서 성공하면 닫힌다")
	void halfOpen_successCloses() {
		CircuitBreaker breaker = halfOpenReadyBreaker();
		TossPaymentClient client = clientWith(breaker);
		breaker.transitionToOpenState();
		breaker.transitionToHalfOpenState();

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(QUERY_URL))
			.andRespond(withSuccess(QUERY_BODY, MediaType.APPLICATION_JSON));

		for (int i = 0; i < 2; i++) {
			client.queryPayment("pk_1");
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	@DisplayName("half-open에서 실패하면 다시 열린다")
	void halfOpen_failureReopens() {
		CircuitBreaker breaker = halfOpenReadyBreaker();
		TossPaymentClient client = clientWith(breaker);
		breaker.transitionToOpenState();
		breaker.transitionToHalfOpenState();

		mockServer.expect(ExpectedCount.manyTimes(), requestTo(QUERY_URL))
			.andRespond(withServerError().body(ERROR_BODY).contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.queryPayment("pk_1")).isInstanceOf(TossPaymentException.class);

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	@DisplayName("Worker 브레이커가 열려도 사용자 조회는 된다")
	void breakersAreIndependentPerPath() {
		CircuitBreaker workerBreaker = CircuitBreaker.ofDefaults("worker");
		CircuitBreaker userBreaker = CircuitBreaker.ofDefaults("user");
		workerBreaker.transitionToOpenState();

		TossPaymentClient workerClient = clientWith(workerBreaker);
		TossPaymentClient userClient = clientWith(userBreaker);

		mockServer.expect(ExpectedCount.once(), requestTo(QUERY_URL))
			.andRespond(withSuccess(QUERY_BODY, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> workerClient.queryPayment("pk_1"))
			.isInstanceOf(TossPaymentException.class);
		assertThat(userClient.queryPayment("pk_1")).isNotNull();
	}
}
