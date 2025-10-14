package com.sudo.raillo.booking.success;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.success.SuccessCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TicketSuccess implements SuccessCode {

	TICKET_LIST_GET_SUCCESS(HttpStatus.OK, "정상적으로 조회되었습니다.");

	private final HttpStatus status;
	private final String message;
}
