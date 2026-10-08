package com.sudo.raillo.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러 코드 enum이 구현하는 인터페이스.
 *
 * <p>코드 형식({@code {DOMAIN}_{NNN}})·접두사·카테고리 밴드 컨벤션 → docs/error-code-convention.md
 */
public interface ErrorCode {

	String getMessage();
	HttpStatus getStatus();
	String getCode();

	/**
	 * 이 에러 코드로 실패한 작업을 다시 실행하면 결과가 달라질 수 있는지. 재시도되는 대상은 에러 코드가 아니라
	 * <b>그 코드로 실패한 작업</b>(Outbox 행 처리, 요청 재전송 등)이다. 기본값은 {@code true}다.
	 *
	 * <p>재시도 큐(Outbox 등)가 백오프를 태울지, 바로 포기할지를 이 값으로 가른다. 데이터가 이미 깨져 있거나
	 * 입력 자체가 영구히 무효한 경우처럼 재시도가 결과를 바꿀 수 없는 에러만 {@code false}로 선언한다.
	 * 판단을 에러 쪽에 두는 이유는, 재시도가 도움이 되는지를 아는 쪽이 에러를 던진 코드이고 재시도를 돌리는 쪽이
	 * 아니기 때문이다. 큐마다 같은 분기를 다시 쓰지 않아도 되고, 새 재시도 큐가 생겨도 이 선언을 그대로 쓴다.</p>
	 */
	default boolean failedWorkRetryable() {
		return true;
	}
}
