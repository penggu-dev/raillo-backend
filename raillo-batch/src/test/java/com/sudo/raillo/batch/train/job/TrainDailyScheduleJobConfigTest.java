package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import com.sudo.raillo.train.cache.TrainCacheKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.transaction.PlatformTransactionManager;

class TrainDailyScheduleJobConfigTest {

	private TrainScheduleBatchFacade trainScheduleBatchFacade;
	private TrainDailyScheduleJobConfig jobConfig;

	@BeforeEach
	void setUp() {
		trainScheduleBatchFacade = mock(TrainScheduleBatchFacade.class);
		jobConfig = new TrainDailyScheduleJobConfig(
			mock(JobRepository.class),
			mock(PlatformTransactionManager.class),
			trainScheduleBatchFacade
		);
	}

	@DisplayName("운행일 파라미터가 있으면 지정 날짜의 일일 스케줄을 생성한다")
	@Test
	void daily_tasklet_uses_operation_date_parameter() throws Exception {
		// given
		LocalDate operationDate = LocalDate.of(2026, 9, 12);
		var tasklet = jobConfig.trainDailyScheduleTasklet(operationDate.toString());

		// when
		tasklet.execute(null, null);

		// then
		verify(trainScheduleBatchFacade).createTrainSchedule(List.of(operationDate));
	}

	@DisplayName("운행일 파라미터가 없으면 마지막 운행일 다음 스케줄을 생성한다")
	@Test
	void daily_tasklet_uses_next_operation_date_when_parameter_is_missing() throws Exception {
		// given
		var tasklet = jobConfig.trainDailyScheduleTasklet(null);

		// when
		tasklet.execute(null, null);

		// then
		verify(trainScheduleBatchFacade).createTrainSchedule();
	}

	@DisplayName("잘못된 운행일 파라미터는 날짜 형식 오류를 발생시킨다")
	@Test
	void daily_tasklet_rejects_invalid_operation_date() {
		// given
		var tasklet = jobConfig.trainDailyScheduleTasklet("2026/09/12");

		// when & then
		assertThatThrownBy(() -> tasklet.execute(null, null))
			.isInstanceOf(DateTimeParseException.class);
	}

	@DisplayName("적재 범위를 오늘부터 대상 날짜까지로 넓혀 이전 실행에서 빠진 캐시를 메운다")
	@Test
	void daily_tasklet_widens_cache_range_from_today() throws Exception {
		// given - 날짜를 생략하면 재실행이 다음 날짜로 넘어가 실패한 날짜가 빠질 수 있다
		LocalDate today = LocalDate.now(TrainCacheKey.ZONE);
		LocalDate targetDate = today.plusDays(5);
		when(trainScheduleBatchFacade.createTrainSchedule()).thenReturn(List.of(targetDate));
		ExecutionContext executionContext = new ExecutionContext();
		ChunkContext chunkContext = chunkContextWith(executionContext);

		// when
		jobConfig.trainDailyScheduleTasklet(null).execute(null, chunkContext);

		// then
		assertThat(executionContext.getString(TrainScheduleCacheContext.START_DATE)).isEqualTo(today.toString());
		assertThat(executionContext.getString(TrainScheduleCacheContext.END_DATE)).isEqualTo(targetDate.toString());
	}

	@DisplayName("과거 날짜를 지정하면 그 날짜만 적재한다")
	@Test
	void daily_tasklet_keeps_past_date_range() throws Exception {
		// given
		LocalDate pastDate = LocalDate.now(TrainCacheKey.ZONE).minusDays(3);
		when(trainScheduleBatchFacade.createTrainSchedule(List.of(pastDate))).thenReturn(List.of(pastDate));
		ExecutionContext executionContext = new ExecutionContext();
		ChunkContext chunkContext = chunkContextWith(executionContext);

		// when
		jobConfig.trainDailyScheduleTasklet(pastDate.toString()).execute(null, chunkContext);

		// then
		assertThat(executionContext.getString(TrainScheduleCacheContext.START_DATE)).isEqualTo(pastDate.toString());
		assertThat(executionContext.getString(TrainScheduleCacheContext.END_DATE)).isEqualTo(pastDate.toString());
	}

	private ChunkContext chunkContextWith(ExecutionContext executionContext) {
		ChunkContext chunkContext = mock(ChunkContext.class, RETURNS_DEEP_STUBS);
		when(chunkContext.getStepContext().getStepExecution().getExecutionContext()).thenReturn(executionContext);
		return chunkContext;
	}
}
