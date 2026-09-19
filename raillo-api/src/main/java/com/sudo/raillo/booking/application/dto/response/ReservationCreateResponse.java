package com.sudo.raillo.booking.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 생성 응답 DTO")
public record ReservationCreateResponse(
	@Schema(description = "생성된 예약 ID", example = "RV20260917120000A1B2C3")
	String reservationId,

	@Schema(description = "예약 만료 일시. 이 시각까지 결제를 시작해야 한다", example = "2026-09-17T12:10:00")
	LocalDateTime expiresAt
) {
}
