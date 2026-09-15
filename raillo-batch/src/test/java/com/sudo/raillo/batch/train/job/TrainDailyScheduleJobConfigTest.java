package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
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
}
