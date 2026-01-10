package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 예매 상태 enum
 */
@Getter
@RequiredArgsConstructor
public enum BookingStatus {

	BOOKED("예매완료", "예매가 완료된 상태"),
	CANCELLED("예매취소", "예매가 취소된 상태");

	private final String displayName;
	private final String description;
}
