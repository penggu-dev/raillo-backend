package com.sudo.raillo.booking.docs;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.request.CartCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.global.exception.error.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "CartBookings")
public interface CartControllerDoc {

	@Operation(method = "POST", summary = "장바구니 예약 등록", description = "장바구니에 예약을 등록합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "201", description = "장바구니에 예약이 등록되었습니다."),
		@ApiResponse(responseCode = "403", description = "본인의 예약만 장바구니에 등록할 수 있습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404",
			description = "예약 장바구니 등록에 실패하였습니다:\n"
				+ "- 예약 정보를 찾을 수 없음\n"
				+ "- 사용자를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409", description = "이미 등록된 예약입니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
	})
	SuccessResponse<?> createCartBooking(CartCreateRequest request, UserDetails userDetails);

	@Operation(method = "GET", summary = "장바구니 예약 목록 조회", description = "장바구니에 담긴 예약 목록을 조회합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "장바구니를 성공적으로 조회했습니다."),
		@ApiResponse(responseCode = "404", description = "사용자 정보를 찾을 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<List<BookingDetail>> getCartBookings(@AuthenticationPrincipal UserDetails userDetails);
}
