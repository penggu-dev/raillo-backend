package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TicketStatus {
	ISSUED("발급완료"),
	USED("사용완료"),
	CANCELLED("취소");

	private final String description;
}
