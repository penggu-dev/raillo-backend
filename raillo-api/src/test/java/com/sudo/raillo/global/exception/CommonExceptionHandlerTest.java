package com.sudo.raillo.global.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sudo.raillo.global.response.ErrorResponse;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.payment.domain.exception.PaymentError;

/**
 * 도메인 불변식 위반의 HTTP 응답 매핑을 검증한다.
 *
 * <p>{@link DomainException}은 {@link BusinessException}의 하위 타입이 아니라 형제다. 전용 핸들러가 없으면
 * 포괄 핸들러로 떨어져, 예외가 들고 있는 ErrorCode를 버리고 500을 돌려준다.</p>
 */
class CommonExceptionHandlerTest {

	private final CommonExceptionHandler handler = new CommonExceptionHandler();

	@Test
	@DisplayName("도메인 불변식 위반은 ErrorCode가 정한 상태와 코드로 응답한다")
	void domainException_usesCarriedErrorCode() {
		DomainException ex = new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);

		ResponseEntity<ErrorResponse> response = handler.handleDomainException(ex);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().getErrorCode())
			.isEqualTo(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE.getCode());
	}

	@Test
	@DisplayName("다른 상태를 가진 도메인 오류도 각자의 상태로 응답한다")
	void domainException_keepsEachStatus() {
		DomainException ex = new DomainException(OrderError.ORDER_IS_EXPIRED);

		ResponseEntity<ErrorResponse> response = handler.handleDomainException(ex);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
		assertThat(response.getBody().getErrorCode()).isEqualTo(OrderError.ORDER_IS_EXPIRED.getCode());
	}
}
