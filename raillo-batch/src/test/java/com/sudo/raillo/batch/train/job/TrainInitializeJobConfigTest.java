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

class TrainInitializeJobConfigTest {

	@DisplayName("초기화 Job은 생성한 데이터를 곧바로 캐시에 반영하는 순서로 실행한다")
	@Test
	void initialize_job_caches_right_after_each_generation_step() {
		// given
		Step parseStep = mockStep("trainParseStep");
		Step staticCacheStep = mockStep("trainStaticCacheStep");
		Step monthlyStep = mockStep("trainMonthlyScheduleStep");
		Step scheduleCacheStep = mockStep("trainScheduleCacheStep");

		// when
		Job job = new TrainInitializeJobConfig().trainInitializeJob(
			mock(JobRepository.class), parseStep, staticCacheStep, monthlyStep, scheduleCacheStep);

		// then - 적재 Step은 각각 대상 데이터를 만든 Step 뒤에 와야 한다
		assertThat(job).isInstanceOf(SimpleJob.class);
		assertThat(job.getName()).isEqualTo(TrainInitializeJobConfig.JOB_NAME);
		assertThat(((SimpleJob)job).getStepNames()).containsExactly(
			"trainParseStep", "trainStaticCacheStep", "trainMonthlyScheduleStep", "trainScheduleCacheStep");
	}

	private Step mockStep(String name) {
		Step step = mock(Step.class);
		when(step.getName()).thenReturn(name);
		return step;
	}
}
