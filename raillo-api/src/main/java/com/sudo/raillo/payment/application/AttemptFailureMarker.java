package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.ErrorCodeCarrier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * attempt 종결 마킹을 "실패해도 원래 오류를 가리지 않는" 형태로 감싼다.
 *
 * <p>마킹 실패를 삼키고 로그만 남기는 블록이 호출자마다 복사돼 있었고 로그용 에러 코드 추출까지 두 클래스에
 * 같은 내용으로 들어가 있었다. 무엇을 삼키는지와 어떤 형식으로 남기는지는 한 곳에만 있어야 한다.
 *
 * <p>{@link PaymentAttemptManager}의 메서드로 두지 않는다. 같은 빈 안에서 호출하면 Spring 프록시를 거치지 않아
 * {@code REQUIRES_NEW}가 조용히 사라지고, 그 전파 설정이 이 경로의 존재 이유다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttemptFailureMarker {

	private final PaymentAttemptManager paymentAttemptManager;

	/**
	 * attempt를 FAILED로 바꾸고, 마킹 자체가 실패하면 로그만 남기고 삼킨다.
	 *
	 * <p>호출자는 이미 사용자에게 돌려줄 원래 실패 사유를 들고 있다. 마킹이 Payment 잠금 대기 등으로 실패했다고
	 * 그 사유를 가리면 안 된다. 마킹이 안 된 attempt는 IN_PROGRESS로 남아 Recovery Worker가 이어서 대사한다.</p>
	 */
	public void markFailedQuietly(Long paymentId, Long attemptDbId, AttemptError error) {
		try {
			paymentAttemptManager.markFailedInNewTransaction(paymentId, attemptDbId, error);
		} catch (RuntimeException markingError) {
			log.error("[attempt 실패 마킹 중 오류] paymentId={}, attemptDbId={}, markingErrorCode={}",
				paymentId, attemptDbId, errorCodeOf(markingError), markingError);
		}
	}

	/**
	 * 로그용 코드. 에러 코드를 싣는 예외면 그 코드를, 아니면 예외 클래스명을 쓴다.
	 *
	 * <p>{@code BusinessException}만 보던 분기를 {@link ErrorCodeCarrier}로 바꿨다. 코드를 싣는 예외가 셋인데
	 * 하나만 보면 {@code DomainException}·{@code RedisException}은 코드 대신 클래스명으로 남는다.</p>
	 */
	private static String errorCodeOf(RuntimeException e) {
		return e instanceof ErrorCodeCarrier carrier
			? String.valueOf(carrier.getErrorCode())
			: e.getClass().getSimpleName();
	}
}
