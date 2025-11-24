package com.sudo.raillo.booking.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "임시 예약 생성 응답")
public record ProvisionalBookingResponse(

	@Schema(description = "예약 ID (UUID)", example = "abc-123-def-456")
	String bookingId,

	@Schema(description = "만료 시간", example = "2024-01-01T10:10:00")
	LocalDateTime expiresAt,

	@Schema(description = "총 요금", example = "50000")
	Integer totalFare
) {
}
