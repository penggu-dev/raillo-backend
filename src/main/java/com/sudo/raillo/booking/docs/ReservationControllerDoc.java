package com.sudo.raillo.booking.docs;

import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Reservations")
public interface ReservationControllerDoc {

	@Operation(method = "POST", summary = "예약 생성", description = "정보를 받아 예약을 수행합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "201", description = "예약이 성공적으로 생성되었습니다."),
		@ApiResponse(responseCode = "400",
			description = "예약 생성에 실패하였습니다:\n"
				+ "- 요청 본문이 유효하지 않음\n"
				+ "- 좌석의 객차 타입이 동일하지 않음\n"
				+ "- 운행 취소된 열차\n"
				+ "- 운행중인 스케줄이 아님\n"
				+ "- 요청 좌석 수와 승객 수가 일치하지 않음",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404",
			description = "예약 생성에 실패하였습니다:\n"
				+ "- 좌석을 찾을 수 없음\n"
				+ "- 요금 정보를 찾을 수 없음\n"
				+ "- 열차 정보를 찾을 수 없음\n"
				+ "- 역 정보를 찾을 수 없음\n"
				+ "- 사용자를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<ReservationCreateResponse> createReservation(ReservationCreateRequest request, UserDetails userDetails);

	@Operation(method = "DELETE", summary = "예약 삭제", description = "예약 ID를 받아 해당 예약을 삭제합니다.")
	@ApiResponses(value = {
		@ApiResponse(responseCode = "204", description = "예약이 성공적으로 삭제되었습니다.")
	})
	SuccessResponse<?> deleteReservation(ReservationDeleteRequest request);

	@Operation(method = "GET", summary = "예약 상세 조회", description = "예약 ID를 받아 해당 예약의 상세 정보를 조회합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "예약 상세 정보 조회에 성공했습니다."),
		@ApiResponse(responseCode = "404",
			description = "예약 상세 조회에 실패하였습니다:\n"
				+ "- 사용자를 찾을 수 없음\n"
				+ "- 예약 정보를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "410", description = "예약이 만료되었습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<ReservationDetail> getReservation(Long reservationId, UserDetails userDetails);

	@Operation(method = "GET", summary = "예약 목록 조회", description = "인증된 사용자의 모든 예약 목록을 조회합니다.", security = {
		@SecurityRequirement(name = "bearerAuth")})
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "예약 목록 조회에 성공했습니다."),
		@ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<List<ReservationDetail>> getReservations(UserDetails userDetails);

}
