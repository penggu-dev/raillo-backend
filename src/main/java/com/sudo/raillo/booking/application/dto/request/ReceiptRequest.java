package com.sudo.raillo.booking.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영수증 조회 요청 DTO")
public record ReceiptRequest(

	@Schema(description = "티켓 ID", example = "1")
	Long ticketId
) {
}
