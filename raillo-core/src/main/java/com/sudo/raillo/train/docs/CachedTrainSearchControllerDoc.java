package com.sudo.raillo.train.docs;

import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "Trains V2")
public interface CachedTrainSearchControllerDoc {

	@Operation(
		method = "GET",
		summary = "운행 캘린더 조회",
		description = "금일로부터 한 달간의 운행 캘린더를 조회해 예약 가능한 날짜 정보를 제공합니다. "
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "운행 캘린더 조회가 완료되었습니다."
		),
		@ApiResponse(
			responseCode = "500",
			description = "서버 내부 오류가 발생했습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	SuccessResponse<List<OperationCalendarItemResponse>> getOperationCalendar();
}
