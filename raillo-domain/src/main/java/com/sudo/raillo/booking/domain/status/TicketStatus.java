package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TicketStatus {

	ISSUED("발급완료", "티켓이 발급된 상태"),
	USED("사용완료", "티켓이 사용된 상태"),
	CANCELLED("취소", "티켓이 취소된 상태 (환불)");

	private final String displayName;
	private final String description;
}
