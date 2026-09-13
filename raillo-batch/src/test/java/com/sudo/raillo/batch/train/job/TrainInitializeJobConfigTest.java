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

	@DisplayName("초기화 Job은 파싱 단계 다음에 월간 스케줄 단계를 실행한다")
	@Test
	void initialize_job_defines_parse_before_monthly_step() {
		// given
		Step parseStep = mock(Step.class);
		Step monthlyStep = mock(Step.class);
		when(parseStep.getName()).thenReturn("trainParseStep");
		when(monthlyStep.getName()).thenReturn("trainMonthlyScheduleStep");

		// when
		Job job = new TrainInitializeJobConfig()
			.trainInitializeJob(mock(JobRepository.class), parseStep, monthlyStep);

		// then
		assertThat(job).isInstanceOf(SimpleJob.class);
		assertThat(job.getName()).isEqualTo(TrainInitializeJobConfig.JOB_NAME);
		assertThat(((SimpleJob)job).getStepNames())
			.containsExactly("trainParseStep", "trainMonthlyScheduleStep");
	}
}
