package com.sudo.raillo.booking.domain.type;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PassengerSummary {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PassengerType passengerType;

	@Column(nullable = false)
	private int count;

	public PassengerSummary(PassengerType passengerType, int count) {
		this.passengerType = passengerType;
		this.count = count;
	}
}
