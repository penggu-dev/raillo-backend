package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SeatOccupancyStatus {

	HELD("점유중", "예약이 임시로 좌석을 점유한 상태 (expiresAt 이후 만료)"),
	CONFIRMED("확정", "결제 완료로 예매가 좌석을 확정 점유한 상태 (만료되지 않음)");

	private final String displayName;
	private final String description;
}
