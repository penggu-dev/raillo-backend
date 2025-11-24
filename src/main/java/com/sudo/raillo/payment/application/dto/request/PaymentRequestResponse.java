package com.sudo.raillo.payment.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "결제 요청 응답 DTO")
public record PaymentRequestResponse(
	@Schema(description = "결제 ID", example = "1")
	Long paymentId,

	@Schema(description = "결제 페이지 URL", example = "https://payment.toss.im/...")
	String paymentUrl,

	@Schema(description = "결제 금액", example = "50000")
	Integer amount
) {
}
