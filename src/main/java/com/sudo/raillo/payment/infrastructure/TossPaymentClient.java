package com.sudo.raillo.payment.infrastructure;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.payment.infrastructure.dto.TossErrorResponseV1;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentCancelResponse;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.TossPaymentCancelRequest;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentClient {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient tossPaymentRestClient;
	private final ObjectMapper objectMapper;

	/**
	 * 토스페이먼츠 결제 승인 API 호출
	 *
	 * @param request 결제 승인 요청 (paymentKey, orderId, amount)
	 * @return Payment 객체 -> TossPaymentConfirmResponse 변환
	 * <ul>
	 * 	   <li>성공: 200 OK + Payment 객체</li>
	 *     <li>실패: 4xx, 5xx 에러</li>
	 * </ul>
	 */
	public TossPaymentConfirmResponse confirmPayment(PaymentConfirmRequest request) {
		log.info("토스 결제 승인 요청: paymentKey={}, orderId={}, amount={}",
			request.paymentKey(), request.orderId(), request.amount());

		try {
			TossPaymentConfirmResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/confirm")
				.body(request)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "결제 승인");
					}
					return res.bodyTo(TossPaymentConfirmResponse.class);
				});

			log.info("[TOSS] 결제 승인 성공: paymentKey={}, orderId={}, status={}",
				response.paymentKey(), response.orderId(), response.status());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			log.error("[TOSS] 결제 승인 중 알 수 없는 예외 발생", e);
			throw new BusinessException(
				PaymentError.PAYMENT_SYSTEM_ERROR,
				"결제 승인 처리 중 알 수 없는 오류가 발생했습니다: " + e.getMessage()
			);
		}
	}

	/**
	 * 토스페이먼츠 결제 취소 API 호출
	 *
	 * @param paymentKey 결제 키
	 * @param request 취소 요청 (cancelReason 필수, cancelAmount는 부분 취소 시에만)
	 * @return Payment 객체 -> TossPaymentCancelResponse 변환
	 *
	 * <h4>멱등키(Idempotency-Key) 적용 방법</h4>
	 * <p>현재는 사용하지 않지만, 재시도 로직이나 배치 실패 복구가 필요할 경우 적용 가능</p>
	 * <pre>{@code
	 * // 요청 시 헤더 추가
	 * .header("Idempotency-Key", UUID.randomUUID().toString())
	 *
	 * // 같은 멱등키로 재시도하면 토스가 캐시된 응답 반환 (중복 취소 방지)
	 * // 멱등키는 15일간 유효
	 * }</pre>
	 */
	public TossPaymentCancelResponse cancelPayment(String paymentKey, TossPaymentCancelRequest request) {
		String idempotencyKey = generateIdempotencyKey();

		log.info("[TOSS] 결제 취소 요청: paymentKey={}, cancelReason={}, cancelAmount={}, idempotencyKey={}",
			paymentKey, request.cancelReason(), request.cancelAmount(), idempotencyKey);

		try {
			TossPaymentCancelResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/{paymentKey}/cancel", paymentKey)
				.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.body(request)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "결제 취소");
					}
					return res.bodyTo(TossPaymentCancelResponse.class);
				});

			log.info("[TOSS] 결제 취소 성공: paymentKey={}, status={}, balanceAmount={}, cancelCount={}",
				response.paymentKey(), response.status(), response.balanceAmount(), response.getCancelCount());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			log.error("[TOSS] 결제 취소 중 알 수 없는 예외 발생", e);
			throw new BusinessException(
				PaymentError.PAYMENT_SYSTEM_ERROR,
				"결제 취소 처리 중 알 수 없는 오류가 발생했습니다: " + e.getMessage()
			);
		}
	}

	private String generateIdempotencyKey() {
		return UUID.randomUUID().toString();
	}

	private void handleErrorResponse(ClientHttpResponse res, String operation) throws IOException {
		int statusCode = res.getStatusCode().value();

		// 진단 로그 — body 파싱 전에 먼저 찍어야 함
		// Content-Type 없음(null) → 과거 application/octet-stream 버그의 직접 단서
		// bodyBytes = 0 → body 소실, > 0 → body 정상 수신
		log.warn("[TOSS] {} 에러 응답: httpStatus={}, responseImpl={}, Content-Type={}, Content-Length={}",
			operation, statusCode,
			res.getClass().getSimpleName(),
			res.getHeaders().getContentType(),
			res.getHeaders().getContentLength());

		byte[] rawBytes = res.getBody().readAllBytes();
		log.warn("[TOSS] {} 에러 body: bytes={}", operation, rawBytes.length);

		String raw = new String(rawBytes, StandardCharsets.UTF_8);
		log.warn("[TOSS] {} 응답 헤더: traceId={}, server={}, date={}",
			operation,
			res.getHeaders().getFirst("x-tosspayments-trace-id"),
			res.getHeaders().getFirst("server"),
			res.getHeaders().getFirst("date"));

		if (rawBytes.length == 0) {
			String message = "토스 에러 응답 본문이 비어 있습니다. (httpStatus=" + statusCode + ")";
			if (res.getStatusCode().is5xxServerError()) {
				log.error("[TOSS] {} 실패 ({}): {}", operation, statusCode, message);
			} else {
				log.warn("[TOSS] {} 실패 ({}): {}", operation, statusCode, message);
			}
			throw new TossPaymentException(statusCode, "EMPTY_ERROR_BODY", message);
		}

		TossErrorResponseV1 error;
		try {
			error = objectMapper.readValue(raw, TossErrorResponseV1.class);
		} catch (IOException e) {
			String bodySnippet = truncateForLog(raw);
			String message = "토스 에러 응답 파싱 실패 (httpStatus=" + statusCode + ")";
			log.error("[TOSS] {} 실패 ({}): {} bodySnippet={}", operation, statusCode, message, bodySnippet, e);
			throw new TossPaymentException(statusCode, "UNPARSABLE_ERROR_BODY", message + ", body=" + bodySnippet);
		}

		if (res.getStatusCode().is5xxServerError()) {
			log.error("[TOSS] {} 실패 (5xx): httpStatus={}, code={}, message={}",
				operation, statusCode, error.code(), error.message());
		} else {
			log.warn("[TOSS] {} 실패 (4xx): httpStatus={}, code={}, message={}",
				operation, statusCode, error.code(), error.message());
		}

		throw new TossPaymentException(statusCode, error.code(), error.message());
	}

	private String truncateForLog(String raw) {
		String normalized = raw.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= 500) {
			return normalized;
		}
		return normalized.substring(0, 500) + "...(truncated)";
	}
}
