package com.sudo.raillo.booking.docs;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.global.response.ErrorResponse;
import com.sudo.raillo.global.response.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Reservations")
public interface ReservationControllerDoc {

	@Operation(method = "POST", summary = "예약 생성",
		description = "좌석을 점유하고 예약을 생성합니다. 예약은 응답의 만료 시각까지 유지되며 그 전에 결제를 시작해야 합니다.",
		security = {@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "201", description = "예약이 성공적으로 생성되었습니다."),
		@ApiResponse(responseCode = "400",
			description = "예약 생성에 실패하였습니다:\n"
				+ "- 요청 본문이 유효하지 않음\n"
				+ "- 승객 수와 좌석 수가 다름\n"
				+ "- 같은 좌석을 중복 선택함\n"
				+ "- 좌석의 객차 타입이 동일하지 않음\n"
				+ "- 운행이 취소된 열차\n"
				+ "- 출발역과 도착역 순서가 유효하지 않음\n"
				+ "- 출발 5분 전 예약 마감 이후",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404",
			description = "예약 생성에 실패하였습니다:\n"
				+ "- 열차 스케줄을 찾을 수 없음\n"
				+ "- 운행에 없는 역\n"
				+ "- 좌석을 찾을 수 없음\n"
				+ "- 구간 운임을 찾을 수 없음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "409",
			description = "좌석 충돌:\n"
				+ "- 다른 사용자가 예약 중인 구간\n"
				+ "- 이미 예매된 구간",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "500", description = "좌석 점유 처리 중 오류가 발생했습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<ReservationCreateResponse> createReservation(ReservationCreateRequest request, UserDetails userDetails);
}
