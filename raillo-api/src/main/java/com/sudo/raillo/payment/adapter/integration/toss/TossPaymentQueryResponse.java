package com.sudo.raillo.payment.adapter.integration.toss;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 토스 결제 조회(GET /v1/payments/{paymentKey}) 응답에서 회복 로직에 필요한 최소 필드만 정의한다.
 * 응답에는 이 외에도 receipt, card, virtualAccount 등 다양한 필드가 있으나 회복에는 사용하지 않는다.
 */
@Schema(description = "토스 결제 조회 응답")
public record TossPaymentQueryResponse(

	@Schema(description = "결제 고유 키")
	String paymentKey,

	@Schema(description = "주문번호")
	String orderId,

	@Schema(description = "결제 수단(한글). 상태가 DONE이 아닐 경우 null일 수 있다.")
	String method,

	@Schema(description = "총 결제 금액")
	Long totalAmount,

	@Schema(description = "결제 처리 상태",
		example = "DONE",
		allowableValues = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "DONE",
			"CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED"})
	String status
) {
}
