package com.sudo.raillo.payment.infrastructure;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.TossErrorResponseV1;
import com.sudo.raillo.payment.application.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentClient {

	private final RestClient tossPaymentRestClient;
	private final ObjectMapper objectMapper;

	/**
	 * 토스페이먼츠 결제 승인 API 호출
	 */
	public TossPaymentConfirmResponse confirmPayment(PaymentConfirmRequest request) {
		log.info("토스 결제 승인 요청: paymentKey={}, orderId={}, amount={}",
			request.paymentKey(), request.orderId(), request.amount());

		try {
			TossPaymentConfirmResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/confirm")
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					String raw = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);

					TossErrorResponseV1 error = objectMapper.readValue(raw, TossErrorResponseV1.class);
					log.warn("[TOSS] 결제 승인 실패: httpStatus={}, code={}, message={}",
						res.getStatusCode().value(), error.code(), error.message());

					throw new TossPaymentException(res.getStatusCode().value(), error.code(), error.message());
				})
				.body(TossPaymentConfirmResponse.class);

			log.info("토스 결제 승인 성공: ", response.paymentKey(), response);

			return response;

		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			log.error("토스 결제 승인 중 알 수 없는 예외 발생", e);
			throw new BusinessException(
				PaymentError.TOSS_PAYMENT_FAILED,
				"결제 처리 중 알 수 없는 오류가 발생했습니다: " + e.getMessage()
			);
		}
	}
}
