package com.sudo.raillo.train.presentation;

import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.application.facade.CachedTrainSearchFacade;
import com.sudo.raillo.train.docs.CachedTrainSearchControllerDoc;
import com.sudo.raillo.train.success.TrainSearchSuccess;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trains")
@RequiredArgsConstructor
@Tag(name = "열차 조회 V2", description = "캐시가 적용된 열차 스케줄, 객차, 좌석 정보 조회 API")
@Slf4j
public class CachedTrainSearchController implements CachedTrainSearchControllerDoc {

	private final CachedTrainSearchFacade cachedTrainSearchFacade;

	@GetMapping("/calendar")
	public SuccessResponse<List<OperationCalendarItemResponse>> getOperationCalendar() {
		log.info("운행 캘린더 조회");
		List<OperationCalendarItemResponse> calendar = cachedTrainSearchFacade.getOperationCalendar();
		log.info("운행 캘린더 조회: {} 건", calendar.size());

		return SuccessResponse.of(TrainSearchSuccess.OPERATION_CALENDAR_SUCCESS, calendar);
	}
}
