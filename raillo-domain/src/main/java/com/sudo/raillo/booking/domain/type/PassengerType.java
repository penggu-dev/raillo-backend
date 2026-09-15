package com.sudo.raillo.booking.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PassengerType {
	ADULT("어른"),
	CHILD("어린이"),
	INFANT("유아"),
	SENIOR("경로"),
	DISABLED_HEAVY("중증 장애인"),
	DISABLED_LIGHT("경증 장애인"),
	VETERAN("국가 유공자");

	private final String description;
}
