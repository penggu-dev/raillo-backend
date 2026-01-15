package com.sudo.raillo.global.event.domain;

import com.sudo.raillo.global.exception.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EventError implements ErrorCode {

	EVENT_NOT_PROGRESS("진행 상태로 변경할 수 없는 이벤트입니다.", HttpStatus.BAD_REQUEST, "E_001"),
	EVENT_NOT_COMPLETABLE("완료 상태로 변경할 수 없는 이벤트입니다.", HttpStatus.BAD_REQUEST, "E_002"),
	EVENT_NOT_RETRYABLE("재시도 할 수 없는 이벤트입니다.", HttpStatus.BAD_REQUEST, "E_003"),
	EVENT_JSON_SERIALIZATION_FAIL("이벤트 직렬화에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "E_004"),
	EVENT_JSON_DESERIALIZATION_FAIL("이벤트 역직렬화에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "E_005"),
	UNKNOWN_EVENT("알수없는 이벤트 요청입니다.", HttpStatus.BAD_REQUEST, "E_006"),
	EVENT_NOT_FOUND("이벤트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "E_007");

	private final String message;
	private final HttpStatus status;
	private final String code;
}

