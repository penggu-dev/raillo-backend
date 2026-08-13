package com.sudo.raillo.booking.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReservationStatus {

	HELD("점유중", "좌석을 임시 점유하고 결제를 기다리는 상태"),
	CONFIRMED("확정", "결제가 완료되어 예매로 확정된 상태"),
	RELEASED("해제", "사용자 취소 또는 만료로 좌석 점유가 해제된 상태");

	private final String displayName;
	private final String description;
}
