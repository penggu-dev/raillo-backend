package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.application.service.TrainCalendarService;
import com.sudo.raillo.train.batch.CalendarCacheEvictScheduler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ServiceTest
class OperationCalendarCacheTest {

	@MockitoBean
	private TrainCalendarService trainCalendarService;

	@Autowired
	private CachedTrainSearchFacade cachedTrainSearchFacade;

	@Autowired
	private CalendarCacheEvictScheduler calendarCacheEvictScheduler;

	private List<OperationCalendarItemResponse> mockResponse;

	@BeforeEach
	void setUp() {
		mockResponse = List.of(
			OperationCalendarItemResponse.of(LocalDate.now(), false, true)
		);
	}

	@Test
	@DisplayName("DB에서 반환된 원본 데이터와 캐시를 통해 조회된 데이터가 일치한다")
	void should_return_same_data_from_cache_as_db() {
		// given
		given(trainCalendarService.getOperationCalendar()).willReturn(mockResponse);

		// when
		List<OperationCalendarItemResponse> dbData = cachedTrainSearchFacade.getOperationCalendar(); // db에서 조회
		List<OperationCalendarItemResponse> cacheData = cachedTrainSearchFacade.getOperationCalendar(); // cache에서 조회

		// then
		assertThat(dbData).isEqualTo(cacheData);
	}

	@Test
	@DisplayName("운행 캘린더 캐시가 없으면 DB를 조회 한다")
	void should_save_operation_calendar_to_cache() {
		// given
		given(trainCalendarService.getOperationCalendar()).willReturn(mockResponse);

		// when
		cachedTrainSearchFacade.getOperationCalendar();

		// then - 캐시가 비어있었으므로 DB 조회가 1회 호출되어야 함
		verify(trainCalendarService, times(1)).getOperationCalendar();
	}

	@Test
	@DisplayName("Redis에 운행 캘린더 캐시가 존재하면 DB를 조회하지 않는다")
	void should_not_query_db_when_cache_hit() {
		// given
		given(trainCalendarService.getOperationCalendar()).willReturn(mockResponse);

		// when
		cachedTrainSearchFacade.getOperationCalendar();
		cachedTrainSearchFacade.getOperationCalendar();

		// Then - 메서드를 2번 호출했지만 실제 DB 조회는 1회만 발생해야 함
		verify(trainCalendarService, times(1)).getOperationCalendar();
	}

	@Test
	@DisplayName("자정이 지나면 운행 캘린더 캐시가 제거된다")
	void should_evict_calendar_cache_at_midnight() {
		// given
		given(trainCalendarService.getOperationCalendar()).willReturn(mockResponse);
		cachedTrainSearchFacade.getOperationCalendar();

		// when
		calendarCacheEvictScheduler.evictCalendarCache();
		cachedTrainSearchFacade.getOperationCalendar();

		// then - 캐시 제거로 인해 DB조회가 2회 호출되어야 함
		verify(trainCalendarService, times(2)).getOperationCalendar();
	}
}
