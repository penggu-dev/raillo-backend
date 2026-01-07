package com.sudo.raillo.booking.success;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.success.SuccessCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TicketSuccess implements SuccessCode {

	TICKET_LIST_SUCCESS(HttpStatus.OK, "티켓 목록을 성공적으로 조회했습니다."),
	RECEIPT_SUCCESS(HttpStatus.OK, "영수증을 성공적으로 조회했습니다.");

	private final HttpStatus status;
	private final String message;
}
