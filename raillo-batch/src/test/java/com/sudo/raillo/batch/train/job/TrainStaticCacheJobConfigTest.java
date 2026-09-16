package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.sudo.raillo.batch.train.application.facade.StationFareBatchFacade;
import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import com.sudo.raillo.batch.train.application.service.TrainStaticCacheService;

class TrainStaticCacheJobConfigTest {

	@DisplayName("정적 기준정보 적재 Tasklet은 적재를 한 번 수행하고 끝난다")
	@Test
	void tasklet_loads_static_reference_once() throws Exception {
		// given
		TrainStaticCacheService service = mock(TrainStaticCacheService.class);
		TrainStaticCacheJobConfig jobConfig =
			new TrainStaticCacheJobConfig(mock(JobRepository.class), service);

		// when
		RepeatStatus status = jobConfig.trainStaticCacheTasklet().execute(null, null);

		// then
		assertThat(status).isEqualTo(RepeatStatus.FINISHED);
		org.mockito.Mockito.verify(service).loadAll();
	}

	@DisplayName("단독 실행할 수 있도록 Job 이름과 Step이 등록된다")
	@Test
	void registers_standalone_job() {
		// given - Redis만 다시 채우는 복구 경로가 필요해 단독 Job으로 둔다
		TrainStaticCacheJobConfig jobConfig =
			new TrainStaticCacheJobConfig(mock(JobRepository.class), mock(TrainStaticCacheService.class));

		// when
		Job job = jobConfig.trainStaticCacheLoadJob();

		// then
		assertThat(job.getName()).isEqualTo(TrainStaticCacheJobConfig.JOB_NAME);
		assertThat(((SimpleJob)job).getStepNames()).containsExactly("trainStaticCacheStep");
	}

	@DisplayName("파싱 Job은 파싱 다음에 정적 기준정보를 다시 적재한다")
	@Test
	void parse_job_reloads_static_cache_after_parsing() {
		// given - 파싱이 운임과 템플릿을 갈아치우므로 캐시도 따라가야 한다
		Step staticCacheStep = mock(Step.class);
		when(staticCacheStep.getName()).thenReturn("trainStaticCacheStep");

		TrainParseJobConfig jobConfig = new TrainParseJobConfig(
			mock(JobRepository.class),
			mock(org.springframework.transaction.PlatformTransactionManager.class),
			mock(TrainScheduleBatchFacade.class),
			mock(StationFareBatchFacade.class));

		// when
		Job job = jobConfig.trainParseJob(staticCacheStep);

		// then
		assertThat(((SimpleJob)job).getStepNames())
			.containsExactly("trainParseStep", "trainStaticCacheStep");
	}
}
