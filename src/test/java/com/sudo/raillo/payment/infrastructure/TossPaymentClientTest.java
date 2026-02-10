package com.sudo.raillo.payment.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.TossPaymentCancelRequest;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;
import com.sudo.raillo.payment.infrastructure.dto.TossCancelDetail;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentCancelResponse;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;

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
			PaymentConfirmRequest request = new PaymentConfirmRequest(
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
				.andExpect(method(HttpMethod.POST))
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
			PaymentConfirmRequest request = new PaymentConfirmRequest(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "REJECT_CARD_PAYMENT",
					"message": "카드 결제가 거절되었습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withBadRequest().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException) e;
					assertThat(ex.getHttpStatus()).isEqualTo(400);
					assertThat(ex.getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
					assertThat(ex.getMessage()).isEqualTo("카드 결제가 거절되었습니다.");
				});

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 시 TossPaymentException으로 변환된다")
		void fail_5xx() {
			// given
			PaymentConfirmRequest request = new PaymentConfirmRequest(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "PROVIDER_ERROR",
					"message": "일시적인 오류가 발생했습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withServerError().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException) e;
					assertThat(ex.getHttpStatus()).isEqualTo(500);
					assertThat(ex.getErrorCode()).isEqualTo("PROVIDER_ERROR");
				});

			server.verify();
		}

		@Test
		@DisplayName("예상치 못한 예외 발생 시 PAYMENT_SYSTEM_ERROR BusinessException으로 래핑된다")
		void fail_unexpectedException() {
			// given
			PaymentConfirmRequest request = new PaymentConfirmRequest(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			// 응답 body 없이 연결 실패 시뮬레이션 - 잘못된 JSON으로 파싱 실패 유도
			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR)
				.hasMessageContaining("결제 승인 처리 중 알 수 없는 오류가 발생했습니다");

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
				.andExpect(method(HttpMethod.POST))
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
				.andExpect(method(HttpMethod.POST))
				.andRespond(withBadRequest().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException) e;
					assertThat(ex.getHttpStatus()).isEqualTo(400);
					assertThat(ex.getErrorCode()).isEqualTo("ALREADY_CANCELED_PAYMENT");
					assertThat(ex.getMessage()).isEqualTo("이미 취소된 결제입니다.");
				});

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
				.andExpect(method(HttpMethod.POST))
				.andRespond(withServerError().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(TossPaymentException.class)
				.satisfies(e -> {
					TossPaymentException ex = (TossPaymentException) e;
					assertThat(ex.getHttpStatus()).isEqualTo(500);
					assertThat(ex.getErrorCode()).isEqualTo("PROVIDER_ERROR");
					assertThat(ex.getMessage()).isEqualTo("일시적인 오류가 발생했습니다.");
				});

			server.verify();
		}

		@Test
		@DisplayName("예상치 못한 예외 발생 시 PAYMENT_SYSTEM_ERROR BusinessException으로 래핑된다")
		void fail_unexpectedException() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR)
				.hasMessageContaining("결제 취소 처리 중 알 수 없는 오류가 발생했습니다");

			server.verify();
		}
	}

	@Nested
	@DisplayName("Authorization 헤더")
	class AuthorizationHeader {

		@Test
		@DisplayName("secretKey가 Base64로 인코딩되어 Authorization 헤더에 포함된다")
		void authorizationHeaderContainsBase64EncodedSecretKey() {
			// given
			PaymentConfirmRequest request = new PaymentConfirmRequest(
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
