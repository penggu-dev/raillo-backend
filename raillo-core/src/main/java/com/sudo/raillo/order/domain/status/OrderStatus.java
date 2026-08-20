package com.sudo.raillo.order.domain.status;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {

	PENDING("주문대기", "좌석 선점 완료, 결제 대기 중"),
	ORDERED("주문완료", "결제가 완료된 상태"),
	EXPIRED("만료", "결제 시간 초과로 만료된 상태");

	private final String displayName;
	private final String description;
}
