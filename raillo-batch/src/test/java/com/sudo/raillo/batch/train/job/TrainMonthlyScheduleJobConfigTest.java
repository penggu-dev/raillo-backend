package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

class TrainMonthlyScheduleJobConfigTest {

	@DisplayName("월간 Tasklet은 실행일부터 한 달 뒤까지의 날짜를 생성한다")
	@Test
	@SuppressWarnings("unchecked")
	void monthly_tasklet_creates_one_month_date_range() throws Exception {
		// given
		TrainScheduleBatchFacade trainScheduleBatchFacade = mock(TrainScheduleBatchFacade.class);
		TrainMonthlyScheduleJobConfig jobConfig = new TrainMonthlyScheduleJobConfig(
			mock(JobRepository.class),
			mock(PlatformTransactionManager.class),
			trainScheduleBatchFacade
		);
		LocalDate beforeExecution = LocalDate.now();
		ArgumentCaptor<List<LocalDate>> datesCaptor = ArgumentCaptor.forClass(List.class);

		// when
		jobConfig.trainMonthlyScheduleTasklet().execute(null, null);

		// then
		verify(trainScheduleBatchFacade).createTrainSchedule(datesCaptor.capture());
		List<LocalDate> dates = datesCaptor.getValue();
		assertThat(dates.getFirst()).isBetween(beforeExecution, LocalDate.now());
		assertThat(dates.getLast()).isEqualTo(dates.getFirst().plusMonths(1));
	}
}
