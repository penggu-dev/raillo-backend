package com.sudo.raillo.train.application.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.TrainScheduleTimeInfo;
import com.sudo.raillo.train.domain.Train;

@ServiceTest
@DisplayName("TrainScheduleService 캐시 테스트")
class TrainScheduleServiceCacheTest {

	private static final String CACHE_NAME = "trainScheduleTimeInfo";

	@Autowired
	private TrainScheduleService trainScheduleService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private CacheManager cacheManager;

	private Train train;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		// 캐시 초기화
		var cache = cacheManager.getCache(CACHE_NAME);
		if (cache != null) {
			cache.clear();
		}

		train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
	}

	@Test
	@DisplayName("getTrainScheduleTimeInfo 호출 시 캐시에 저장된다")
	void getTrainScheduleTimeInfo_cachesResult() {
		// given
		Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
		var cache = cacheManager.getCache(CACHE_NAME);

		// 캐시가 비어있는지 확인
		assertThat(cache).isNotNull();
		assertThat(cache.get(trainScheduleId)).isNull();

		// when
		TrainScheduleTimeInfo result = trainScheduleService.getTrainScheduleTimeInfo(trainScheduleId);

		// then - 캐시에 저장되었는지 확인
		var cachedValue = cache.get(trainScheduleId);
		assertThat(cachedValue).isNotNull();
		assertThat(cachedValue.get()).isEqualTo(result);
	}

	@Test
	@DisplayName("동일한 trainScheduleId로 여러 번 호출 시 캐시된 값을 반환한다")
	void getTrainScheduleTimeInfo_returnsCachedValue() {
		// given
		Long trainScheduleId = trainScheduleResult.trainSchedule().getId();

		// when - 두 번 호출
		TrainScheduleTimeInfo firstCall = trainScheduleService.getTrainScheduleTimeInfo(trainScheduleId);
		TrainScheduleTimeInfo secondCall = trainScheduleService.getTrainScheduleTimeInfo(trainScheduleId);

		// then - 같은 객체를 반환 (캐시에서 가져옴)
		assertThat(firstCall).isEqualTo(secondCall);
		assertThat(firstCall.id()).isEqualTo(trainScheduleId);
		assertThat(firstCall.operationDate()).isEqualTo(trainScheduleResult.trainSchedule().getOperationDate());
		assertThat(firstCall.departureTime()).isEqualTo(trainScheduleResult.trainSchedule().getDepartureTime());
		assertThat(firstCall.arrivalTime()).isEqualTo(trainScheduleResult.trainSchedule().getArrivalTime());
	}

	@Test
	@DisplayName("서로 다른 trainScheduleId는 각각 캐시된다")
	void getTrainScheduleTimeInfo_cachesDifferentIds() {
		// given
		TrainScheduleResult anotherSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 002")
			.train(train)
			.operationDate(LocalDate.now().plusDays(2))
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		Long scheduleId1 = trainScheduleResult.trainSchedule().getId();
		Long scheduleId2 = anotherSchedule.trainSchedule().getId();
		var cache = cacheManager.getCache(CACHE_NAME);

		// when
		TrainScheduleTimeInfo result1 = trainScheduleService.getTrainScheduleTimeInfo(scheduleId1);
		TrainScheduleTimeInfo result2 = trainScheduleService.getTrainScheduleTimeInfo(scheduleId2);

		// then - 각각 캐시에 저장됨
		assertThat(cache.get(scheduleId1)).isNotNull();
		assertThat(cache.get(scheduleId2)).isNotNull();
		assertThat(result1.id()).isEqualTo(scheduleId1);
		assertThat(result2.id()).isEqualTo(scheduleId2);
		assertThat(result1).isNotEqualTo(result2);
	}
}
