package com.sudo.raillo.payment.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sudo.raillo.global.exception.ErrorCode;
import com.sudo.raillo.global.response.ErrorResponse;
import com.sudo.raillo.payment.application.exception.PaymentGatewayException;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.extern.slf4j.Slf4j;

/**
 * 게이트웨이가 HTTP 응답을 돌려주지 않은 실패의 사용자 응답을 정한다.
 *
 * <p>{@code CommonExceptionHandler}는 {@code ExternalApiException}의 {@code httpStatus}를 그대로 응답
 * 상태로 쓴다. 응답을 받지 못한 실패는 그 값이 0이라 쓸 수 없고, 전송 단계에 따라 사용자에게 할 말도 다르다.
 * 요청이 나가지 않았으면 바로 다시 시도하면 되고, 결과를 모르면 기다렸다 확인해야 한다.</p>
 *
 * <p>도메인별 처리를 {@code global}이 알지 않도록 {@code AuthExceptionHandler}와 같은 방식으로 분리하고,
 * 공통 핸들러보다 먼저 잡도록 우선순위를 높인다.</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PaymentGatewayExceptionHandler {

	@ExceptionHandler(PaymentGatewayException.class)
	public ResponseEntity<ErrorResponse> handlePaymentGatewayException(PaymentGatewayException ex) {
		if (ex.getHttpStatus() != 0) {
			// 게이트웨이가 응답한 실패는 그 상태 코드를 그대로 쓴다. 공통 핸들러와 같은 규칙이다.
			Map<String, Object> details = Map.of("provider", ex.getProvider(), "type", ex.getErrorType());
			return ResponseEntity.status(ex.getHttpStatus())
				.body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), details));
		}

		ErrorCode errorCode = ex.isNotReached()
			? PaymentError.PAYMENT_GATEWAY_NOT_SENT
			: PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS;

		log.warn("[결제 게이트웨이 응답 없음] notReached={}, gatewayCode={}, message={}",
			ex.isNotReached(), ex.getErrorCode(), ex.getMessage());

		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}
}
