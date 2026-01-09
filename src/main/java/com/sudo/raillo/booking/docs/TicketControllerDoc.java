package com.sudo.raillo.booking.docs;

import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.userdetails.UserDetails;

@Tag(name = "Tickets")
public interface TicketControllerDoc {

	@Operation(method = "GET", summary = "영수증 조회", description = "특정 티켓의 영수증 정보를 조회합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")
	})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "영수증이 성공적으로 조회되었습니다."),
		@ApiResponse(responseCode = "404", description = "티켓을 찾을 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<ReceiptResponse> getReceipt(Long ticketId, UserDetails userDetails);
}
