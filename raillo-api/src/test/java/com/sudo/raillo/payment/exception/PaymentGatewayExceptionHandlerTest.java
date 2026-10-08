package com.sudo.raillo.payment.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sudo.raillo.global.response.ErrorResponse;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentException;
import com.sudo.raillo.payment.application.exception.DeliveryPhase;
import com.sudo.raillo.payment.domain.exception.PaymentError;

/**
 * 게이트웨이 예외의 HTTP 응답 매핑을 검증한다.
 *
 * <p>응답을 받지 못한 실패는 HTTP 상태가 없어 예외의 {@code httpStatus}가 0이다. 그 값을 그대로 응답
 * 상태로 쓰면 안 되고, 전송 단계에 따라 다른 상태와 코드로 내려가야 한다.</p>
 */
class PaymentGatewayExceptionHandlerTest {

	private final PaymentGatewayExceptionHandler handler = new PaymentGatewayExceptionHandler();

	@Test
	@DisplayName("전송되지 않은 게이트웨이 실패는 503과 재시도 가능 코드로 응답한다")
	void notReached_respondsServiceUnavailable() {
		TossPaymentException failure = new TossPaymentException(0, "CONFIRM_NOT_SENT",
			"결제 요청이 전송되지 않았습니다.", DeliveryPhase.NOT_REACHED);

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_GATEWAY_NOT_SENT.getCode());
	}

	@Test
	@DisplayName("응답을 받지 못한 결과 불명 실패는 409 처리 중으로 응답한다")
	void noResponse_respondsConflictInProgress() {
		TossPaymentException failure = new TossPaymentException(0, "CONFIRM_OUTCOME_UNKNOWN",
			"결제 결과를 확인하지 못했습니다.", DeliveryPhase.NO_RESPONSE);

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS.getCode());
	}

	@Test
	@DisplayName("게이트웨이가 응답한 실패는 그 상태 코드를 그대로 쓴다")
	void answered_keepsGatewayStatus() {
		TossPaymentException failure = new TossPaymentException(400, "REJECT_CARD_PAYMENT", "카드 거절");

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
	}
}
