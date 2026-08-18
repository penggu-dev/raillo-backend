package com.sudo.raillo.booking.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 생성 응답 DTO")
public record PendingBookingCreateResponse(
	@Schema(description = "생성된 예약 ID", example = "e029f64f-405e-49c7-87a3-5a0c32607142")
	String pendingBookingId
) {
}
