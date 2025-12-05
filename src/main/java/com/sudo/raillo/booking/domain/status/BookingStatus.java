package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 예약 상태 enum
 */
@Getter
@RequiredArgsConstructor
public enum BookingStatus {

	BOOKED("예약완료", "예약이 완료된 상태"),
	CANCELLED("예약취소", "예약이 취소된 상태");

	private final String displayName;
	private final String description;
}
