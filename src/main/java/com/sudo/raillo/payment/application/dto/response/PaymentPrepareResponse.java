package com.sudo.raillo.payment.application.dto.response;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제 준비 응답 DTO")
public record PaymentPrepareResponse(

	@Schema(description = "주문 ID")
	String orderId,

	@Schema(description = "결제 금액", example = "50000")
	BigDecimal amount
) {
}
