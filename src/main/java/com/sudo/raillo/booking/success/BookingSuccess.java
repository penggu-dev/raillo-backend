package com.sudo.raillo.booking.success;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.success.SuccessCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingSuccess implements SuccessCode {

	BOOKING_CREATE_SUCCESS(HttpStatus.CREATED, "예매가 성공적으로 생성되었습니다."),
	BOOKING_DELETE_SUCCESS(HttpStatus.NO_CONTENT, "예매가 성공적으로 삭제되었습니다."),
	BOOKING_LIST_SUCCESS(HttpStatus.OK, "승차권을 포함한 예매 목록을 성공적으로 조회했습니다."),
	BOOKING_DETAIL_SUCCESS(HttpStatus.OK, "승차권을 포함한 예매 상세를 성공적으로 조회했습니다.");

	private final HttpStatus status;
	private final String message;
}
