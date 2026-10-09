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
 * 게이트웨이 호출 실패의 사용자 응답을 정한다. 상태 코드는 승인 여부가 확정됐는지로 가른다.
 *
 * <p>{@code CommonExceptionHandler}는 {@code ExternalApiException}의 {@code httpStatus}를 그대로 응답
 * 상태로 쓴다. 승인은 멱등하지 않아 그 규칙을 그대로 쓸 수 없다. 응답을 받지 못한 실패는 값이 0이라 쓸 수
 * 없고, 게이트웨이가 5xx로 답한 실패는 값이 있어도 그대로 내리면 안 된다. 5xx는 승인한 뒤 응답에 실패한
 * 경우를 포함하므로, 5xx에 자동 재시도를 걸어둔 호출자가 있으면 승인 요청이 다시 나가 이중 청구가 된다.
 * 멱등하지 않은 호출을 5xx 재시도 대상에서 빼는 것은 {@code TossGetRetryStrategy}가 Toss GET에만 재시도를
 * 허용하는 것과 같은 판단이다.</p>
 *
 * <p>그래서 세 분기를 {@link PaymentGatewayException}의 판정 메서드에 1:1로 맞춘다. 결과 불명은 재시도를
 * 부르지 않는 409로, 확정 거절(4xx)은 게이트웨이 상태 그대로, 미도달 확정은 바로 재시도해도 되는 503으로
 * 내린다. 상태 코드 유무는 기준이 아니다. 조회 경로는 미도달 확정에도 504/502를 실어 보내므로, 상태 코드로
 * 가르면 재시도해도 되는 실패가 재시도하면 안 되는 실패처럼 나간다. 같은 판정 메서드를
 * {@code PaymentConfirmService}가 attempt 상태 기록에 쓰므로 둘의 기준이 같아야 한다.</p>
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
		if (ex.isOutcomeUnknown()) {
			// 승인 여부를 모르는 실패. 응답을 받지 못한 경우와 게이트웨이가 5xx로 답한 경우가 모두 여기다.
			// 호출 실패 자체는 TossPaymentClient가 남기므로 여기서는 사용자에게 돌려준 결정을 남긴다.
			log.warn("[결제 게이트웨이 결과 불명] httpStatus={}, gatewayCode={}, message={}",
				ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage());
			return respond(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		}

		if (ex.isDefinitiveFailure()) {
			// 확정 거절은 그 상태 코드를 그대로 쓴다. 공통 핸들러와 같은 규칙이다.
			Map<String, Object> details = Map.of("provider", ex.getProvider(), "type", ex.getErrorType());
			return ResponseEntity.status(ex.getHttpStatus())
				.body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), details));
		}

		if (ex.isNotReached()) {
			// 호출이 나가지 않은 것이 확정이므로 중복 승인 위험이 없다. 사용자가 바로 다시 시도할 수 있다.
			log.warn("[결제 게이트웨이 미전송] gatewayCode={}, message={}", ex.getErrorCode(), ex.getMessage());
			return respond(PaymentError.PAYMENT_GATEWAY_NOT_SENT);
		}

		// 세 분류 중 어디에도 들어가지 않는 조합. 전송 단계가 늘어나면 상태 코드와 무관하게 여기로 떨어진다.
		// 기본값은 재시도를 막는 쪽에 두고, 조합 자체는 error로 남겨 분기 누락을 드러낸다.
		log.error("[결제 게이트웨이 실패 분류 불가] httpStatus={}, gatewayCode={}, message={}",
			ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage());
		return respond(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
	}

	private static ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
	}
}
