package com.sudo.raillo.train.docs;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.sudo.raillo.global.exception.error.ErrorResponse;
import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItem;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Trains")
public interface TrainSearchControllerDoc {

	@Operation(
		method = "GET",
		summary = "운행 캘린더 조회",
		description = "금일로부터 한 달간의 운행 캘린더를 조회해 예약 가능한 날짜 정보를 제공합니다."
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
	SuccessResponse<List<OperationCalendarItem>> getOperationCalendar();

	@Operation(
		method = "POST",
		summary = "열차 스케줄 검색",
		description = "출발역, 도착역, 운행 날짜, 승객 수, 출발 시간을 기준으로 열차 스케줄을 검색합니다. " +
			"페이징을 지원하며 잔여 좌석 정보와 요금 정보를 함께 제공합니다."
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "열차 조회가 완료되었습니다."
		),
		@ApiResponse(
			responseCode = "400",
			description = "잘못된 검색 조건입니다. (운행 날짜는 오늘 이후여야 합니다 / 승객 수는 1명 이상 9명 이하여야 합니다 / 출발역과 도착역이 동일하거나 유효하지 않은 경로입니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "404",
			description = "검색 조건에 맞는 열차가 없습니다. (해당 날짜에 운행하는 열차가 없습니다 / 해당 구간의 요금 정보를 찾을 수 없습니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "500",
			description = "열차 시스템 오류가 발생했습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	SuccessResponse<TrainSearchSlicePageResponse> searchTrainSchedules(
		TrainSearchRequest request,
		@Parameter(description = "페이징 정보 (page, size)", hidden = true) Pageable pageable
	);

	@Operation(
		method = "POST",
		summary = "열차 객차 목록 조회",
		description = "선택한 열차의 잔여 좌석이 있는 객차 목록을 조회합니다. " +
			"프론트엔드에서 객차 선택용 드롭다운에 사용되며, 추천 객차 정보도 함께 제공합니다. " +
			"승객 수와 구간을 고려하여 예약 가능한 객차만 반환합니다."
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "열차 객차 목록 조회가 완료되었습니다."
		),
		@ApiResponse(
			responseCode = "400",
			description = "잘못된 요청입니다. (출발역과 도착역이 동일하거나 유효하지 않은 경로입니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "404",
			description = "데이터를 찾을 수 없습니다. (해당 열차 스케줄을 찾을 수 없습니다 / 존재하지 않는 역입니다 / 잔여 좌석이 있는 객차가 없습니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "500",
			description = "열차 시스템 오류가 발생했습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	SuccessResponse<TrainCarListResponse> getAvailableTrainCars(TrainCarListRequest request);

	@Operation(
		method = "POST",
		summary = "열차 객차 좌석 상세 조회",
		description = "선택한 객차의 모든 좌석 정보를 상세 조회합니다. " +
			"좌석 선택 화면에서 사용되며, 각 좌석의 예약 가능 여부를 제공합니다. " +
			"좌석 번호, 좌석 타입(일반실/특실), 창가/통로 여부 등의 상세 정보를 포함합니다."
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "열차 객차 좌석 상세 조회가 완료되었습니다."
		),
		@ApiResponse(
			responseCode = "400",
			description = "잘못된 요청입니다. (출발역과 도착역이 동일하거나 유효하지 않은 경로입니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "404",
			description = "데이터를 찾을 수 없습니다. (해당 객차를 찾을 수 없습니다 / 해당 열차 스케줄을 찾을 수 없습니다 / 존재하지 않는 역입니다)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@ApiResponse(
			responseCode = "500",
			description = "열차 시스템 오류가 발생했습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	SuccessResponse<TrainCarSeatDetailResponse> getTrainCarSeatDetail(TrainCarSeatDetailRequest request);

}
