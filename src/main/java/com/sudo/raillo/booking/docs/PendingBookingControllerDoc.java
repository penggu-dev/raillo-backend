package com.sudo.raillo.booking.docs;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Bookings")
public interface PendingBookingControllerDoc {

	@Operation(method = "POST", summary = "임시 예약 생성", description = "정보를 받아 임시 예약을 생성합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "201", description = "임시 예약이 성공적으로 생성되었습니다."),
		@ApiResponse(responseCode = "400",
			description = "임시 예약 생성에 실패하였습니다:\n"
				+ "- 요청 본문이 유효하지 않음\n"
				+ "- 좌석의 객차 타입이 동일하지 않음\n"
				+ "- 운행 취소된 열차\n"
				+ "- 운행중인 스케줄이 아님\n"
				+ "- 요청 좌석 수와 승객 수가 일치하지 않음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404",
			description = "임시 예약 생성에 실패하였습니다:\n"
				+ "- 좌석을 찾을 수 없음\n"
				+ "- 요금 정보를 찾을 수 없음\n"
				+ "- 열차 정보를 찾을 수 없음\n"
				+ "- 역 정보를 찾을 수 없음\n"
				+ "- 사용자를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<PendingBookingCreateResponse> createPendingBooking(
		PendingBookingCreateRequest request, UserDetails userDetails);
}
