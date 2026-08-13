package com.sudo.raillo.booking.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 생성 응답 DTO")
public record ReservationCreateResponse(
	@Schema(description = "생성된 예약 코드", example = "PB20260814120000A1B2C3")
	String reservationCode
) {
}
