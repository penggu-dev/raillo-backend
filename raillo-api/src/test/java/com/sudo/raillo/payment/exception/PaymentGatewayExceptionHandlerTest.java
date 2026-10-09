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

	@Test
	@DisplayName("게이트웨이가 5xx로 응답한 결과 불명 실패는 409 처리 중으로 응답한다")
	void answeredServerError_respondsConflictInProgress() {
		TossPaymentException failure = new TossPaymentException(500, "FAILED_INTERNAL_SYSTEM_PROCESSING",
			"내부 시스템 처리 작업이 실패했습니다.");

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS.getCode());
	}

	@Test
	@DisplayName("전송 단계로 분류되지 않는 실패는 재시도를 막는 409 처리 중으로 응답한다")
	void unclassified_respondsConflictInProgress() {
		// 응답을 받았다고 표시되면서 상태 코드가 없는 조합. 현재 생성 경로에는 없지만, 분류가 늘어나도
		// 기본값이 재시도를 막는 쪽에 남아야 한다. 미전송으로 떨어지면 사용자 재시도가 이중 청구가 된다.
		TossPaymentException failure = new TossPaymentException(0, "UNCLASSIFIED", "분류되지 않은 실패");

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS.getCode());
	}

	@Test
	@DisplayName("미도달이 확정인데 상태 코드가 실린 실패는 503 재시도 가능 코드로 응답한다")
	void notReachedWithGatewayStatus_respondsServiceUnavailable() {
		// 조회 경로의 queryUncertain은 미도달 확정에도 504/502를 싣는다. 상태 코드 유무가 아니라 전송
		// 단계로 갈라야 재시도 가능 여부가 바르게 전달된다.
		TossPaymentException failure = new TossPaymentException(504, "QUERY_UNCERTAIN_TIMEOUT",
			"결제 조회 결과를 확인하지 못했습니다.", DeliveryPhase.NOT_REACHED);

		ResponseEntity<ErrorResponse> response = handler.handlePaymentGatewayException(failure);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_GATEWAY_NOT_SENT.getCode());
	}
}
