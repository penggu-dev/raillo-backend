package com.sudo.raillo.batch.global.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.DefaultApplicationArguments;

class BatchJobLauncherTest {

	private JobOperator jobOperator;
	private Job dailyJob;
	private BatchJobLauncher launcher;

	@BeforeEach
	void setUp() {
		jobOperator = mock(JobOperator.class);
		dailyJob = mock(Job.class);
		Job parseJob = mock(Job.class);
		when(dailyJob.getName()).thenReturn("trainDailySchedule");
		when(parseJob.getName()).thenReturn("trainParse");
		launcher = new BatchJobLauncher(List.of(dailyJob, parseJob), jobOperator);
	}

	@DisplayName("job 옵션으로 지정한 Job을 옵션 파라미터와 자동 생성한 run.id로 실행한다")
	@Test
	void launches_job_with_option_parameters_and_generated_run_id() throws Exception {
		// given
		givenJobFinishesWith(BatchStatus.COMPLETED);
		var args = new DefaultApplicationArguments("--job=trainDailySchedule", "--operationDate=2026-10-20");

		// when
		launcher.run(args);

		// then
		JobParameters parameters = capturedParameters();
		assertThat(parameters.getString("operationDate")).isEqualTo("2026-10-20");
		assertThat(parameters.getLong(BatchJobLauncher.RUN_ID)).isPositive();
		assertThat(parameters.getParameter(BatchJobLauncher.JOB_OPTION)).isNull();
	}

	@DisplayName("점이 포함된 Spring 설정 옵션과 값이 없는 옵션은 Job 파라미터에서 제외한다")
	@Test
	void excludes_spring_property_and_flag_options_from_job_parameters() throws Exception {
		// given
		givenJobFinishesWith(BatchStatus.COMPLETED);
		var args = new DefaultApplicationArguments(
			"--job=trainDailySchedule",
			"--spring.batch.jdbc.initialize-schema=always",
			"--debug"
		);

		// when
		launcher.run(args);

		// then
		JobParameters parameters = capturedParameters();
		assertThat(parameters.parameters())
			.extracting(parameter -> parameter.name())
			.containsExactly(BatchJobLauncher.RUN_ID);
	}

	@DisplayName("Job이 완료되면 종료 코드 0을 반환한다")
	@Test
	void returns_zero_exit_code_when_job_completes() throws Exception {
		// given
		givenJobFinishesWith(BatchStatus.COMPLETED);

		// when
		launcher.run(new DefaultApplicationArguments("--job=trainDailySchedule"));

		// then
		assertThat(launcher.getExitCode()).isZero();
	}

	@DisplayName("Job이 실패하면 0이 아닌 종료 코드를 반환한다")
	@Test
	void returns_non_zero_exit_code_when_job_fails() throws Exception {
		// given
		givenJobFinishesWith(BatchStatus.FAILED);

		// when
		launcher.run(new DefaultApplicationArguments("--job=trainDailySchedule"));

		// then
		assertThat(launcher.getExitCode()).isEqualTo(1);
	}

	@DisplayName("job 옵션이 없으면 사용 가능한 Job 목록과 함께 예외가 발생한다")
	@Test
	void rejects_missing_job_option() {
		// given
		var args = new DefaultApplicationArguments("--operationDate=2026-10-20");

		// when & then
		assertThatThrownBy(() -> launcher.run(args))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--job=<이름>")
			.hasMessageContaining("trainDailySchedule")
			.hasMessageContaining("trainParse");
		verifyNoInteractions(jobOperator);
	}

	@DisplayName("존재하지 않는 Job 이름을 지정하면 예외가 발생한다")
	@Test
	void rejects_unknown_job_name() {
		// given
		var args = new DefaultApplicationArguments("--job=unknownJob");

		// when & then
		assertThatThrownBy(() -> launcher.run(args))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("존재하지 않는 Job입니다: unknownJob");
		verifyNoInteractions(jobOperator);
	}

	private void givenJobFinishesWith(BatchStatus status) throws Exception {
		JobExecution execution = mock(JobExecution.class);
		when(execution.getStatus()).thenReturn(status);
		when(jobOperator.start(eq(dailyJob), any(JobParameters.class))).thenReturn(execution);
	}

	private JobParameters capturedParameters() throws Exception {
		ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
		verify(jobOperator).start(eq(dailyJob), captor.capture());
		return captor.getValue();
	}
}
