package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.exception.RedisError;
import com.sudo.raillo.global.redis.exception.RedisException;
import com.sudo.raillo.payment.domain.exception.PaymentError;

@ExtendWith(MockitoExtension.class)
@DisplayName("AttemptFailureMarker - 마킹 실패를 삼키는 정책")
class AttemptFailureMarkerTest {

	private static final Long PAYMENT_ID = 7L;
	private static final Long ATTEMPT_DB_ID = 42L;
	private static final AttemptError ERROR = new AttemptError("REJECT_CARD_PAYMENT", "카드 승인 거절");

	@InjectMocks
	private AttemptFailureMarker attemptFailureMarker;

	@Mock
	private PaymentAttemptManager paymentAttemptManager;

	@Test
	@DisplayName("마킹을 PaymentAttemptManager에 그대로 위임한다")
	void delegates_to_manager() {
		// when
		attemptFailureMarker.markFailedQuietly(PAYMENT_ID, ATTEMPT_DB_ID, ERROR);

		// then
		verify(paymentAttemptManager).markFailedInNewTransaction(PAYMENT_ID, ATTEMPT_DB_ID, ERROR);
	}

	@Test
	@DisplayName("마킹이 BusinessException으로 실패해도 호출자에게 전파하지 않는다")
	void swallows_business_exception() {
		// given 이 클래스의 존재 이유다. 마킹 실패가 호출자가 들고 있는 원래 실패 사유를 가리면 안 된다.
		doThrow(new BusinessException(PaymentError.PAYMENT_NOT_FOUND))
			.when(paymentAttemptManager).markFailedInNewTransaction(any(), any(), any());

		// when & then
		assertThatCode(() -> attemptFailureMarker.markFailedQuietly(PAYMENT_ID, ATTEMPT_DB_ID, ERROR))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("BusinessException이 아닌 ErrorCodeCarrier로 실패해도 삼킨다")
	void swallows_other_error_code_carriers() {
		// given RedisException은 BusinessException이 아니다. 과거 errorCodeOf가 BusinessException만 보던 탓에
		// 이런 예외는 코드 대신 클래스명으로 남았고, 삼키는 경로는 타입과 무관해야 한다.
		doThrow(new RedisException(RedisError.REDIS_CONNECT_FAIL))
			.when(paymentAttemptManager).markFailedInNewTransaction(eq(PAYMENT_ID), eq(ATTEMPT_DB_ID), any());

		// when & then
		assertThatCode(() -> attemptFailureMarker.markFailedQuietly(PAYMENT_ID, ATTEMPT_DB_ID, ERROR))
			.doesNotThrowAnyException();
	}
}
