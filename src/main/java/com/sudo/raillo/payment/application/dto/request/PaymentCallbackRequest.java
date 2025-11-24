package com.sudo.raillo.payment.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "결제 완료 콜백 DTO")
public record PaymentCallbackRequest(
	@Schema(description = "결제 키", example = "5zJ4xY7m0kODnyRpQWGrN2xqGlNvLrKwv1M9ENjbeoPaZdL6")
	@NotBlank
	String paymentKey,

	@Schema(description = "주문번호 (bookingId)", example = "abc-123-def-456")
	@NotBlank
	String bookingId,

	@Schema(description = "결제 금액", example = "50000")
	@NotNull
	Integer amount
) {
}
