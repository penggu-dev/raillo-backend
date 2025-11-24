package com.sudo.raillo.payment.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "결제 요청 DTO")
public record PaymentRequestRequest(

	@Schema(description = "임시 예약 ID", example = "abc-123-def-456")
	@NotBlank(message = "임시 예약 ID는 필수입니다")
	String bookingId
) {
}
