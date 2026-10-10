package com.sudo.raillo.booking.docs;

import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.global.response.ErrorResponse;
import com.sudo.raillo.global.response.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Bookings")
public interface BookingControllerDoc {

	@Operation(
		method = "DELETE",
		summary = "예매 삭제",
		description = """
			예매 ID를 받아 본인 예매를 삭제합니다. 다른 사용자의 예매는 삭제할 수 없습니다.

			좌석 점유 해제는 삭제가 커밋된 뒤 비동기로 처리되므로, 삭제한 좌석이 재예약 가능해지기까지
			잠시 걸릴 수 있습니다. 예매 목록에서는 즉시 사라집니다.
			""",
		security = {@SecurityRequirement(name = "bearerAuth")}
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "204", description = "예매가 성공적으로 삭제되었습니다."),
		@ApiResponse(responseCode = "400",
			description = "예매 ID가 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "403",
			description = "다른 사용자의 예매입니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "404",
			description = "예매 삭제에 실패하였습니다:\n"
				+ "- 사용자를 찾을 수 없음\n"
				+ "- 예매 정보를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<?> deleteBooking(BookingDeleteRequest request, UserDetails userDetails);

	@Operation(
		method = "GET", summary = "예매와 승차권 상세 조회",
		description = "예매 ID를 받아 해당 확정 승차권을 포함한 예매 상세 정보를 조회합니다.",
		security = {@SecurityRequirement(name = "bearerAuth")}
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "승차권을 포함한 예매 상세 정보 조회에 성공했습니다."),
		@ApiResponse(responseCode = "404",
			description = "승차권을 포함한 예매 상세 조회에 실패하였습니다:\n"
				+ "- 사용자를 찾을 수 없음\n"
				+ "- 예매 정보를 찾을 수 없음\n",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
	})
	SuccessResponse<BookingResponse> getBooking(Long bookingId, UserDetails userDetails);

	@Operation(
		method = "GET",
		summary = "승차권을 포함한 예매 목록 조회",
		description = """
			인증된 사용자의 승차권을 포함한 예매 목록을 조회합니다.

			- **upcoming**: 앞으로 이용 가능한 승차권 (승차권 조회)
			- **history**: 이용 완료 및 취소된 승차권 (구입 이력)
			- **all**: 전체 승차권 조회 (BOOKED 상태만, 기본값)
			""",
		security = {@SecurityRequirement(name = "bearerAuth")}
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "승차권을 포함한 예매 목록 조회에 성공했습니다."),
		@ApiResponse(
			responseCode = "400",
			description = "유효하지 않은 조회 필터입니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "404",
			description = "사용자를 찾을 수 없습니다.",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	SuccessResponse<List<BookingResponse>> getBookings(BookingTimeFilter status, UserDetails userDetails);

}
