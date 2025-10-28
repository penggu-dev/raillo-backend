package com.sudo.raillo.booking.docs;

import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Tickets")
public interface TicketControllerDoc {

	@Operation(method = "GET", summary = "내 티켓 목록 조회", description = "멤버가 소유하고 있는 티켓을 조회합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")
	})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "티켓이 성공적으로 조회되었습니다."),
		@ApiResponse(responseCode = "404", description = "유저를 찾을 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "500", description = "티켓 정보를 가져올 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<List<TicketReadResponse>> getMyTickets(UserDetails userDetails);
}
