package com.sudo.raillo.booking.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TripType {
	OW("편도"),
	RT("왕복");

	private final String description;
}
