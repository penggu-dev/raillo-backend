package com.sudo.raillo.order.exception;

import com.sudo.raillo.global.exception.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderError implements ErrorCode {
	NOT_PENDING("주문 대기 상태가 아닙니다", HttpStatus.BAD_REQUEST, "O_001"),
	NOT_ORDERED("주문 상태가 아닙니다", HttpStatus.BAD_REQUEST, "O_002"),
	INVALID_TOTAL_AMOUNT("주문 총 금액은 0보다 크거나 같아야 합니다", HttpStatus.BAD_REQUEST, "O_003"),
	ORDER_NOT_FOUND("주문 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST, "O_004"),
	ORDER_ACCESS_DENIED("주문에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "O_005");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
