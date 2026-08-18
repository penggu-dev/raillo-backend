package com.sudo.raillo.common.exception;

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
}
