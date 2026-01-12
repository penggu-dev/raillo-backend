package com.sudo.raillo.global.event.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventError implements ErrorCode {

	EVENT_NOT_COMPLETABLE("완료 상태로 변경할 수 없는 이벤트입니다.", HttpStatus.BAD_REQUEST, "E_001"),
	EVENT_NOT_RETRYABLE("재시도 할 수 없는 이벤트입니다.", HttpStatus.BAD_REQUEST, "E_002"),
	EVENT_JSON_SERIALIZATION_FAIL("이벤트 직렬화에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "E_003"),
	EVENT_JSON_DESERIALIZATION_FAIL("이벤트 역직렬화에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "E_004"),
	UNKNOWN_EVENT("알수없는 이벤트 요청입니다.", HttpStatus.BAD_REQUEST, "E_005"),
	EVENT_NOT_FOUND("이벤트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "E_006");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
