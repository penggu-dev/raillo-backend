package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;

@RestClientTest(TossPaymentClient.class)
@Import(TossPaymentClientTest.TestConfig.class)
class TossPaymentClientTest {

	private static final String SECRET_KEY = "test_sk_secret_key";

	static class TestConfig {
		@Bean
		public RestClient tossPaymentRestClient(RestClient.Builder restClientBuilder) {
			String encodedSecretKey = Base64.getEncoder()
				.encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));

			return restClientBuilder
				.baseUrl("https://api.tosspayments.com")
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
		}

		@Bean
		public MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean(destroyMethod = "close")
		public PoolingHttpClientConnectionManager tossHttpConnectionManager() {
			return PoolingHttpClientConnectionManagerBuilder.create().build();
		}

		@Bean
		public TossApiMetrics tossApiMetrics(MeterRegistry meterRegistry,
			PoolingHttpClientConnectionManager tossHttpConnectionManager) {
			return new TossApiMetrics(meterRegistry, tossHttpConnectionManager);
		}
	}

	@Autowired
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MockRestServiceServer server;

	@Autowired
	private ObjectMapper objectMapper;

	@Nested
	@DisplayName("confirmPayment")
	class ConfirmPayment {

		@Test
		@DisplayName("200 응답 시 TossPaymentConfirmResponse로 정상 매핑된다")
		void success() throws Exception {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String responseBody = """
				{
					"paymentKey": "toss_pk_123",
					"orderId": "ORDER_001",
					"method": "카드",
					"totalAmount": 50000,
					"status": "DONE"
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
					.encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8))))
				.andExpect(content().json(objectMapper.writeValueAsString(request)))
				.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

			// when
			TossPaymentConfirmResponse response = tossPaymentClient.confirmPayment(request);

			// then
			assertThat(response.paymentKey()).isEqualTo("toss_pk_123");
			assertThat(response.orderId()).isEqualTo("ORDER_001");
			assertThat(response.method()).isEqualTo("카드");
			assertThat(response.totalAmount()).isEqualTo(50000L);
			assertThat(response.status()).isEqualTo("DONE");

			server.verify();
		}

		@Test
		@DisplayName("4xx 응답 시 TossPaymentException으로 변환된다")
		void fail_4xx() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "REJECT_CARD_PAYMENT",
					"message": "카드 결제가 거절되었습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withBadRequest().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("httpStatus", 400)
				.hasFieldOrPropertyWithValue("errorCode", "REJECT_CARD_PAYMENT")
				.hasMessageContaining("카드 결제가 거절되었습니다.");

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 시 TossPaymentException으로 변환된다")
		void fail_5xx() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "PROVIDER_ERROR",
					"message": "일시적인 오류가 발생했습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withServerError().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("httpStatus", 500)
				.hasFieldOrPropertyWithValue("errorCode", "PROVIDER_ERROR")
				.hasMessageContaining("일시적인 오류가 발생했습니다.");

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 본문이 비어 있어도 TossPaymentException으로 변환된다")
		void fail_5xx_emptyBody() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withServerError());

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("httpStatus", 500)
				.hasFieldOrPropertyWithValue("errorCode", "EMPTY_ERROR_BODY")
				.hasMessage("토스 에러 응답 본문이 비어 있습니다. (httpStatus=500)");

			server.verify();
		}

		@Test
		@DisplayName("응답 본문 파싱 실패는 NO_RESPONSE로 분류되어 결과 불명으로 남는다")
		void fail_unparsableBody_noResponse() {
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.isNotReached()).isFalse();
					assertThat(ex.isOutcomeUnknown()).isTrue();
				});

			server.verify();
		}

		@Test
		@DisplayName("커넥션 획득 타임아웃은 NOT_REACHED로 분류된다")
		void fail_connectionRequestTimeout_notReached() {
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withException(new ConnectionRequestTimeoutException("pool exhausted")));

			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.isNotReached()).isTrue();
					assertThat(ex.isOutcomeUnknown()).isFalse();
				});

			server.verify();
		}

		@Test
		@DisplayName("연결 타임아웃은 SocketTimeoutException 하위지만 NOT_REACHED로 분류된다")
		void fail_connectTimeout_notReached() {
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withException(new ConnectTimeoutException("connect timed out")));

			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> assertThat(((TossPaymentException)e).isNotReached()).isTrue());

			server.verify();
		}

		@Test
		@DisplayName("응답 대기 초과는 NO_RESPONSE로 분류된다")
		void fail_socketTimeout_noResponse() {
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.isNotReached()).isFalse();
					assertThat(ex.isOutcomeUnknown()).isTrue();
				});

			server.verify();
		}

		@Test
		@DisplayName("감싸진 원인 체인에서도 전송 단계를 찾아낸다")
		void fail_wrappedCause_notReached() {
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withException(
					new IOException("wrapper", new ConnectionRequestTimeoutException("pool exhausted"))));

			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> assertThat(((TossPaymentException)e).isNotReached()).isTrue());

			server.verify();
		}
	}

	@Nested
	@DisplayName("cancelPayment")
	class CancelPayment {

		@Test
		@DisplayName("200 응답 시 TossPaymentCancelResponse로 정상 매핑되고 Idempotency-Key 헤더가 포함된다")
		void success() throws Exception {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			String responseBody = """
				{
					"paymentKey": "toss_pk_cancel_123",
					"orderId": "ORDER_CANCEL_001",
					"status": "CANCELED",
					"totalAmount": 50000,
					"balanceAmount": 0,
					"cancels": [
						{
							"transactionKey": "TX_KEY_001",
							"cancelReason": "고객 변심",
							"canceledAt": "2025-01-15T10:30:00+09:00",
							"cancelAmount": 50000,
							"refundableAmount": 0,
							"cancelStatus": "DONE"
						}
					],
					"isPartialCancelable": true
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andExpect(header("Idempotency-Key", org.hamcrest.Matchers.notNullValue()))
				.andExpect(content().json(objectMapper.writeValueAsString(request)))
				.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

			// when
			TossPaymentCancelResponse response = tossPaymentClient.cancelPayment(paymentKey, request);

			// then
			assertThat(response.paymentKey()).isEqualTo("toss_pk_cancel_123");
			assertThat(response.status()).isEqualTo("CANCELED");
			assertThat(response.totalAmount()).isEqualTo(50000);
			assertThat(response.balanceAmount()).isEqualTo(0);
			assertThat(response.cancels()).hasSize(1);
			assertThat(response.isFullyCanceled()).isTrue();

			TossCancelDetail cancelDetail = response.cancels().get(0);
			assertThat(cancelDetail.transactionKey()).isEqualTo("TX_KEY_001");
			assertThat(cancelDetail.cancelReason()).isEqualTo("고객 변심");
			assertThat(cancelDetail.canceledAt()).isNotNull();
			assertThat(cancelDetail.cancelAmount()).isEqualTo(50000);
			assertThat(cancelDetail.refundableAmount()).isEqualTo(0);
			assertThat(cancelDetail.cancelStatus()).isEqualTo("DONE");

			server.verify();
		}

		@Test
		@DisplayName("4xx 응답 시 TossPaymentException으로 변환된다")
		void fail_4xx() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			String errorBody = """
				{
					"code": "ALREADY_CANCELED_PAYMENT",
					"message": "이미 취소된 결제입니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andRespond(withBadRequest().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("httpStatus", 400)
				.hasFieldOrPropertyWithValue("errorCode", "ALREADY_CANCELED_PAYMENT")
				.hasMessageContaining("이미 취소된 결제입니다.");

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 시 TossPaymentException으로 변환된다")
		void fail_5xx() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			String errorBody = """
				{
					"code": "PROVIDER_ERROR",
					"message": "일시적인 오류가 발생했습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andRespond(withServerError().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("httpStatus", 500)
				.hasFieldOrPropertyWithValue("errorCode", "PROVIDER_ERROR")
				.hasMessageContaining("일시적인 오류가 발생했습니다.");

			server.verify();
		}

		@Test
		@DisplayName("응답 본문 파싱 실패는 NO_RESPONSE로 분류되어 결과 불명으로 남는다")
		void fail_unparsableBody_noResponse() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.getErrorCode()).isEqualTo("CANCEL_OUTCOME_UNKNOWN");
					assertThat(ex.isNotReached()).isFalse();
					assertThat(ex.isOutcomeUnknown()).isTrue();
				});

			server.verify();
		}

		@Test
		@DisplayName("취소 요청의 커넥션 획득 타임아웃은 NOT_REACHED로 분류된다")
		void fail_connectionRequestTimeout_notReached() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andRespond(withException(new ConnectionRequestTimeoutException("pool exhausted")));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.getErrorCode()).isEqualTo("CANCEL_NOT_SENT");
					assertThat(ex.isNotReached()).isTrue();
				});

			server.verify();
		}
	}

	@Nested
	@DisplayName("queryPayment")
	class QueryPayment {

		private static final String QUERY_URL = "https://api.tosspayments.com/v1/payments/toss_pk_123";
		private static final String DONE_BODY = """
			{"paymentKey":"toss_pk_123","orderId":"ORDER_001","method":"카드","totalAmount":50000,"status":"DONE"}
			""";

		@Test
		@DisplayName("조회의 결과 불명 경로는 전송 단계가 붙어도 결과 불명으로 남는다")
		void queryUncertain_staysOutcomeUnknown() {
			// given
			server.expect(requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException)e;
					assertThat(ex.getErrorCode()).isEqualTo("QUERY_UNCERTAIN_TIMEOUT");
					assertThat(ex.isOutcomeUnknown()).isTrue();
					assertThat(ex.isDefinitiveFailure()).isFalse();
				});

			server.verify();
		}

		@Test
		@DisplayName("조회가 커넥션 획득 타임아웃이면 NOT_REACHED로 분류된다")
		void query_connectionRequestTimeout_notReached() {
			// given
			server.expect(requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withException(new ConnectionRequestTimeoutException("pool exhausted")));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> assertThat(((TossPaymentException)e).isNotReached()).isTrue());

			server.verify();
		}

		@Test
		@DisplayName("200 응답이면 조회 결과를 그대로 돌려준다")
		void success() {
			// given
			server.expect(requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withSuccess(DONE_BODY, MediaType.APPLICATION_JSON));

			// when
			TossPaymentQueryResponse response = tossPaymentClient.queryPayment("toss_pk_123");

			// then
			assertThat(response.status()).isEqualTo("DONE");
			assertThat(response.orderId()).isEqualTo("ORDER_001");
			server.verify();
		}

		@Test
		@DisplayName("응답 본문 읽기가 한 번 실패하면 다시 조회해 성공 응답을 돌려준다")
		void retries_when_body_read_fails_once() {
			// given
			server.expect(ExpectedCount.once(), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(brokenBody());
			server.expect(ExpectedCount.once(), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withSuccess(DONE_BODY, MediaType.APPLICATION_JSON));

			// when
			TossPaymentQueryResponse response = tossPaymentClient.queryPayment("toss_pk_123");

			// then
			assertThat(response.status()).isEqualTo("DONE");
			server.verify();
		}

		@Test
		@DisplayName("응답 본문 읽기가 세 번 모두 실패하면 QUERY_UNCERTAIN_BODY 코드와 502 상태의 TossPaymentException이 발생한다")
		void throws_uncertain_after_body_retries_exhausted() {
			// given
			server.expect(ExpectedCount.times(3), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(brokenBody());

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("errorCode", "QUERY_UNCERTAIN_BODY")
				.hasFieldOrPropertyWithValue("httpStatus", 502);
			server.verify();
		}

		@Test
		@DisplayName("응답 헤더를 받기 전에 timeout이 나면 다시 조회하지 않고 QUERY_UNCERTAIN_TIMEOUT 코드와 504 상태의 TossPaymentException이 발생한다")
		void does_not_retry_on_timeout() {
			// given
			server.expect(ExpectedCount.once(), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("errorCode", "QUERY_UNCERTAIN_TIMEOUT")
				.hasFieldOrPropertyWithValue("httpStatus", 504);
			server.verify();
		}

		@Test
		@DisplayName("응답 헤더를 받기 전에 timeout이 아닌 IO 오류가 나면 다시 조회하지 않고 QUERY_UNCERTAIN_IO 코드와 502 상태의 TossPaymentException이 발생한다")
		void does_not_retry_on_non_timeout_io_failure() {
			// given
			server.expect(ExpectedCount.once(), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withException(new SocketException("Connection reset")));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("errorCode", "QUERY_UNCERTAIN_IO")
				.hasFieldOrPropertyWithValue("httpStatus", 502);
			server.verify();
		}

		@Test
		@DisplayName("Toss가 404 오류 본문을 돌려주면 다시 조회하지 않고 그 코드의 TossPaymentException을 그대로 던진다")
		void passes_through_not_found() {
			// given
			server.expect(ExpectedCount.once(), requestTo(QUERY_URL)).andExpect(method(GET))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
					.contentType(MediaType.APPLICATION_JSON)
					.body("""
						{"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 정보 입니다."}
						"""));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.queryPayment("toss_pk_123"))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("errorCode", "NOT_FOUND_PAYMENT")
				.hasFieldOrPropertyWithValue("httpStatus", 404);
			server.verify();
		}

		/** 헤더(200, JSON)는 정상이지만 본문을 읽는 순간 연결이 끊기는 응답. */
		private ResponseCreator brokenBody() {
			return request -> {
				InputStream body = new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("Connection reset");
					}
				};
				MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return response;
			};
		}
	}

	@Nested
	@DisplayName("Authorization 헤더")
	class AuthorizationHeader {

		@Test
		@DisplayName("secretKey가 Base64로 인코딩되어 Authorization 헤더에 포함된다")
		void authorizationHeaderContainsBase64EncodedSecretKey() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_auth_test", "ORDER_AUTH", BigDecimal.valueOf(10000));

			String expectedAuth = "Basic " + Base64.getEncoder()
				.encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));

			String responseBody = """
				{
					"paymentKey": "toss_pk_auth_test",
					"orderId": "ORDER_AUTH",
					"method": "카드",
					"totalAmount": 10000,
					"status": "DONE"
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuth))
				.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

			// when
			tossPaymentClient.confirmPayment(request);

			// then
			server.verify();
		}
	}
}
