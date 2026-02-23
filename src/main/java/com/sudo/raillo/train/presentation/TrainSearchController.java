package com.sudo.raillo.train.presentation;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.train.application.facade.TrainSearchFacade;
import com.sudo.raillo.train.application.dto.request.TrainCarListRequest;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarListResponse;
import com.sudo.raillo.train.application.dto.response.TrainCarSeatDetailResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.success.TrainSearchSuccess;
import com.sudo.raillo.train.docs.TrainSearchControllerDoc;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/trains")
@RequiredArgsConstructor
@Slf4j
public class TrainSearchController implements TrainSearchControllerDoc {

	private final TrainSearchFacade trainSearchFacade;

	/**
	 * 운행 캘린더 조회
	 */
	@GetMapping("/calendar")
	public SuccessResponse<List<OperationCalendarItemResponse>> getOperationCalendar() {
		List<OperationCalendarItemResponse> calendar = trainSearchFacade.getOperationCalendar();
		log.info("운행 캘린더 조회: {} 건", calendar.size());

		return SuccessResponse.of(TrainSearchSuccess.OPERATION_CALENDAR_SUCCESS, calendar);
	}

	/**
	 * 열차 스케줄 검색
	 */
	@PostMapping("/search")
	public SuccessResponse<TrainSearchSlicePageResponse> searchTrainSchedules(
		@Valid @RequestBody TrainSearchRequest request,
		@PageableDefault(size = 20, sort = "departureTime")
		@Parameter(description = "페이징 정보 (page, size)", hidden = true) Pageable pageable) {

		log.info("열차 검색 요청: {} -> {}, {}, 승객: {}명, 출발 시간: {}시 이후, page: {}, size: {}",
			request.departureStationId(), request.arrivalStationId(),
			request.operationDate(), request.passengerCount(), request.departureHour(),
			pageable.getPageNumber(), pageable.getPageSize());

		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, pageable);

		return SuccessResponse.of(TrainSearchSuccess.TRAIN_SEARCH_SUCCESS, response);
	}

	/**
	 * 열차 객차 목록 조회 (잔여 좌석이 있는 객차만)
	 */
	@PostMapping("/cars")
	public SuccessResponse<TrainCarListResponse> getAvailableTrainCars(
		@Valid @RequestBody TrainCarListRequest request) {

		log.info("열차 객차 목록 조회 요청: trainScheduleId={}, {}역 -> {}역, 승객={}명",
			request.trainScheduleId(), request.departureStationId(),
			request.arrivalStationId(), request.passengerCount());

		TrainCarListResponse response = trainSearchFacade.getAvailableTrainCars(request);

		log.info("열차 객차 목록 조회 완료: {}개 객차 조회됨, 추천 객차={}",
			response.carInfos().size(), response.recommendedCarNumber());

		return SuccessResponse.of(TrainSearchSuccess.TRAIN_CAR_LIST_SUCCESS, response);
	}

	@PostMapping("/seats")
	public SuccessResponse<TrainCarSeatDetailResponse> getTrainCarSeatDetail(
		@Valid @RequestBody TrainCarSeatDetailRequest request) {
		log.info("열차 객차 좌석 상세 조회 요청: trainCarId={}, trainScheduleId={}, {}역 -> {}역",
			request.trainCarId(), request.trainScheduleId(),
			request.departureStationId(), request.arrivalStationId());

		TrainCarSeatDetailResponse response = trainSearchFacade.getTrainCarSeatDetail(request);

		log.info("열차 객차 좌석 상세 조회 완료: 객차={}, 전체좌석={}, 잔여좌석={}",
			response.carNumber(), response.totalSeatCount(), response.remainingSeatCount());

		return SuccessResponse.of(TrainSearchSuccess.TRAIN_CAR_SEAT_DETAIL_SUCCESS, response);
	}
}
