package com.sudo.raillo.booking.domain.type;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "승객 유형과 인원수")
public class PassengerSummary {

	@Schema(description = "승객 유형 (ERD 참고)", example = "ADULT")
	private PassengerType passengerType;

	@Schema(description = "승객 수", example = "2")
	private int count;

	public PassengerSummary(PassengerType passengerType, int count) {
		this.passengerType = passengerType;
		this.count = count;
	}
}
