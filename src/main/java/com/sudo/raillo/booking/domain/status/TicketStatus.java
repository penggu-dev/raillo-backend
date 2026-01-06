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


	/**
	 * 취소 가능한 상태인지 확인
	 */
	public boolean isCancellable() {
		return this == ISSUED;
	}

	public boolean isUsable() {
		return this == ISSUED;
	}
}
