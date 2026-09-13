package com.sudo.raillo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.batch.RailloBatchApplication;
import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.batch.autoconfigure.JobExecutionEvent;
import org.springframework.boot.batch.autoconfigure.JobExecutionExitCodeGenerator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("test")
@SpringBatchTest
@SpringBootTest(classes = RailloBatchApplication.class)
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
@TestPropertySource(properties = "train.schedule.excel.location=file:missing-train-schedule.xlsx")
class TrainParseJobFailureTest {

	private final JobOperatorTestUtils jobOperatorTestUtils;

	@Autowired
	TrainParseJobFailureTest(
		JobOperatorTestUtils jobOperatorTestUtils,
		@Qualifier("trainParseJob") Job trainParseJob
	) {
		this.jobOperatorTestUtils = jobOperatorTestUtils;
		this.jobOperatorTestUtils.setJob(trainParseJob);
	}

	@DisplayName("시간표 리소스가 없으면 파싱 Job과 프로세스 종료 상태가 실패로 기록된다")
	@Test
	void parse_job_reports_failed_exit_code_when_resource_is_missing() throws Exception {
		// given
		var parameters = new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		// when
		var execution = jobOperatorTestUtils.startJob(parameters);
		var exitCodeGenerator = new JobExecutionExitCodeGenerator();
		exitCodeGenerator.onApplicationEvent(new JobExecutionEvent(execution));

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(execution.getExitStatus().getExitCode()).isEqualTo("FAILED");
		assertThat(execution.getAllFailureExceptions())
			.anyMatch(exception -> exception.getMessage().contains("파일을 읽을 수 없습니다"));
		assertThat(exitCodeGenerator.getExitCode()).isPositive();
	}
}
