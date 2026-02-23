package com.sudo.raillo.order.exception;

import com.sudo.raillo.global.exception.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderError implements ErrorCode {
	NOT_PENDING("주문 대기 상태가 아닙니다", HttpStatus.BAD_REQUEST, "O_001"),
	NOT_ORDERED("결제 완료된 주문 상태가 아닙니다", HttpStatus.BAD_REQUEST, "O_002"),
	INVALID_TOTAL_AMOUNT("주문 총 금액은 0보다 크거나 같아야 합니다", HttpStatus.BAD_REQUEST, "O_003"),
	ORDER_NOT_FOUND("주문 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST, "O_004"),
	ORDER_ACCESS_DENIED("주문에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "O_005"),
	EMPTY_PENDING_BOOKINGS("주문할 예약 정보가 없습니다.", HttpStatus.BAD_REQUEST, "O_006"),
	ORDER_BOOKING_NOT_FOUND("주문 예약 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST, "O_007"),
	ORDER_SEAT_BOOKING_NOT_FOUND("주문 좌석 예약 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST, "O_008"),
	ORDER_IS_EXPIRED("만료된 주문입니다.", HttpStatus.GONE, "O_009");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
