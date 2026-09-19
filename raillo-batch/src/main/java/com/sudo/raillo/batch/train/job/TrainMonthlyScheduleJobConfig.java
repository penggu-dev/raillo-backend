package com.sudo.raillo.batch.train.job;

import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 실행일부터 한 달 뒤까지의 운행 스케줄을 생성한다. 이미 스케줄이 있는 날짜는 건너뛴다.
 */
@Configuration
@RequiredArgsConstructor
public class TrainMonthlyScheduleJobConfig {

	public static final String JOB_NAME = "trainMonthlySchedule";

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final TrainScheduleBatchFacade trainScheduleBatchFacade;

	@Bean
	public Job trainMonthlyScheduleJob(@Qualifier("trainScheduleCacheStep") Step trainScheduleCacheStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainMonthlyScheduleStep())
			.next(trainScheduleCacheStep)
			.build();
	}

	@Bean
	public Step trainMonthlyScheduleStep() {
		return new StepBuilder("trainMonthlyScheduleStep", jobRepository)
			.tasklet(trainMonthlyScheduleTasklet(), transactionManager)
			.listener(TrainScheduleCacheContext.promotionListener())
			.build();
	}

	@Bean
	public Tasklet trainMonthlyScheduleTasklet() {
		return (contribution, chunkContext) -> {
			LocalDate startDate = LocalDate.now();
			LocalDate endDate = startDate.plusMonths(1).plusDays(1);
			List<LocalDate> dates =
				trainScheduleBatchFacade.createTrainSchedule(startDate.datesUntil(endDate).toList());

			TrainScheduleCacheContext.putDates(chunkContext, dates);
			return RepeatStatus.FINISHED;
		};
	}
}
