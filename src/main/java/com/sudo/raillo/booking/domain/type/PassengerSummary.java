package com.sudo.raillo.booking.domain.type;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "승객 유형과 인원수")
public class PassengerSummary {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Schema(description = "승객 유형", example = "ADULT")
	private PassengerType passengerType;

	@Column(nullable = false)
	@Schema(description = "승객 수", example = "2")
	private int count;

	public PassengerSummary(PassengerType passengerType, int count) {
		this.passengerType = passengerType;
		this.count = count;
	}
}
