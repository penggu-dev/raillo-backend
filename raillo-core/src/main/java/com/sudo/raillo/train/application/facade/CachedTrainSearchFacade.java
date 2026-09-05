package com.sudo.raillo.train.application.facade;

import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.application.service.TrainCalendarService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 캐시가 적용된 열차 검색 Facade
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachedTrainSearchFacade {

	private final TrainCalendarService trainCalendarService;

	/**
	 * 운행 캘린더 조회 (캐시 적용)
	 * TTL: 당일 자정(23:59:59)까지 - 날짜가 바뀌면 캐시 만료
	 */
	@Cacheable("train:calendar")
	public List<OperationCalendarItemResponse> getOperationCalendar() {
		log.info("운행 캘린더 캐시 미스 - DB 조회 실행");
		return trainCalendarService.getOperationCalendar();
	}
}
