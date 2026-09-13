package com.sudo.raillo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sudo.raillo.batch.application.facade.StationFareBatchFacade;
import com.sudo.raillo.batch.application.facade.TrainScheduleBatchFacade;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.transaction.PlatformTransactionManager;

class TrainBatchJobConfigTest {

	private TrainScheduleBatchFacade trainScheduleBatchFacade;
	private StationFareBatchFacade stationFareBatchFacade;
	private TrainBatchJobConfig jobConfig;

	@BeforeEach
	void setUp() {
		trainScheduleBatchFacade = mock(TrainScheduleBatchFacade.class);
		stationFareBatchFacade = mock(StationFareBatchFacade.class);
		jobConfig = new TrainBatchJobConfig(
			mock(JobRepository.class),
			mock(PlatformTransactionManager.class),
			trainScheduleBatchFacade,
			stationFareBatchFacade
		);
	}

	@DisplayName("파싱 Tasklet은 시간표와 운임표를 순서대로 처리한다")
	@Test
	void parse_tasklet_processes_schedule_and_fare_in_order() throws Exception {
		// given
		var tasklet = jobConfig.trainParseTasklet();

		// when
		tasklet.execute(null, null);

		// then
		var ordered = inOrder(trainScheduleBatchFacade, stationFareBatchFacade);
		ordered.verify(trainScheduleBatchFacade).parseTrainSchedule();
		ordered.verify(stationFareBatchFacade).parseStationFare();
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

	@DisplayName("월간 Tasklet은 실행일부터 한 달 뒤까지의 날짜를 생성한다")
	@Test
	void monthly_tasklet_creates_one_month_date_range() throws Exception {
		// given
		LocalDate beforeExecution = LocalDate.now();
		var tasklet = jobConfig.trainMonthlyScheduleTasklet();
		ArgumentCaptor<List<LocalDate>> datesCaptor = ArgumentCaptor.forClass(List.class);

		// when
		tasklet.execute(null, null);

		// then
		verify(trainScheduleBatchFacade).createTrainSchedule(datesCaptor.capture());
		List<LocalDate> dates = datesCaptor.getValue();
		assertThat(dates.getFirst()).isBetween(beforeExecution, LocalDate.now());
		assertThat(dates.getLast()).isEqualTo(dates.getFirst().plusMonths(1));
	}

	@DisplayName("초기화 Job은 파싱 단계 다음에 월간 스케줄 단계를 실행한다")
	@Test
	void initialize_job_defines_parse_before_monthly_step() {
		// given
		Step parseStep = mock(Step.class);
		Step monthlyStep = mock(Step.class);
		when(parseStep.getName()).thenReturn("trainParseStep");
		when(monthlyStep.getName()).thenReturn("trainMonthlyScheduleStep");

		// when
		Job job = jobConfig.trainInitializeJob(parseStep, monthlyStep);

		// then
		assertThat(job).isInstanceOf(SimpleJob.class);
		assertThat(((SimpleJob)job).getStepNames())
			.containsExactly("trainParseStep", "trainMonthlyScheduleStep");
	}
}
