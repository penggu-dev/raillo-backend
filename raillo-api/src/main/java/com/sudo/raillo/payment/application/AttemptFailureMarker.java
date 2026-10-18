package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.ErrorCodeCarrier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * attempt 종결 마킹을 감싼다. 마킹이 실패해도 호출자가 들고 있는 원래 오류를 가리지 않는다.
 *
 * <p>{@link PaymentAttemptManager}의 메서드로 두지 않는다. 같은 빈 안에서 호출하면 Spring 프록시를 거치지
 * 않아 {@code REQUIRES_NEW}가 조용히 사라진다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttemptFailureMarker {

	private final PaymentAttemptManager paymentAttemptManager;

	/** attempt를 FAILED로 바꾼다. 마킹이 실패하면 attempt가 IN_PROGRESS로 남고 복구 Worker가 대사한다. */
	public void markFailedQuietly(Long paymentId, Long attemptDbId, AttemptError error) {
		try {
			paymentAttemptManager.markFailedInNewTransaction(paymentId, attemptDbId, error);
		} catch (RuntimeException markingError) {
			log.error("[attempt 실패 마킹 중 오류] paymentId={}, attemptDbId={}, markingErrorCode={}",
				paymentId, attemptDbId, errorCodeOf(markingError), markingError);
		}
	}

	/** attempt를 REVIEW_REQUIRED로 바꾼다. 회복 판정이 수동 확인으로 끝났을 때 쓴다. */
	public void markReviewRequiredQuietly(Long paymentId, Long attemptDbId, AttemptError error) {
		try {
			paymentAttemptManager.markReviewRequiredInNewTransaction(paymentId, attemptDbId, error);
		} catch (RuntimeException markingError) {
			log.error("[attempt 수동 확인 마킹 중 오류] paymentId={}, attemptDbId={}, markingErrorCode={}",
				paymentId, attemptDbId, errorCodeOf(markingError), markingError);
		}
	}

	/** attempt를 NOT_SENT로 바꾼다. */
	public void markNotSentQuietly(Long paymentId, Long attemptDbId, AttemptError error) {
		try {
			paymentAttemptManager.markNotSentInNewTransaction(paymentId, attemptDbId, error);
		} catch (RuntimeException markingError) {
			log.error("[attempt 미전송 마킹 중 오류] paymentId={}, attemptDbId={}, markingErrorCode={}",
				paymentId, attemptDbId, errorCodeOf(markingError), markingError);
		}
	}

	/**
	 * 로그용 코드. 코드를 싣는 예외면 그 코드를, 아니면 예외 클래스명을 쓴다. {@code BusinessException}만
	 * 보면 {@code DomainException}과 {@code RedisException}이 클래스명으로 남으므로
	 * {@link ErrorCodeCarrier}로 받는다.
	 */
	private static String errorCodeOf(RuntimeException e) {
		return e instanceof ErrorCodeCarrier carrier
			? String.valueOf(carrier.getErrorCode())
			: e.getClass().getSimpleName();
	}
}
