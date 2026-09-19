package com.sudo.raillo.batch.train.job;

import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 하루치 운행 스케줄을 생성한다.
 * operationDate 파라미터가 있으면 해당 날짜, 없으면 마지막 운행일의 다음 날짜를 생성한다.
 */
@Configuration
@RequiredArgsConstructor
public class TrainDailyScheduleJobConfig {

	public static final String JOB_NAME = "trainDailySchedule";

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final TrainScheduleBatchFacade trainScheduleBatchFacade;

	@Bean
	public Job trainDailyScheduleJob(@Qualifier("trainScheduleCacheStep") Step trainScheduleCacheStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainDailyScheduleStep())
			.next(trainScheduleCacheStep)
			.build();
	}

	@Bean
	public Step trainDailyScheduleStep() {
		return new StepBuilder("trainDailyScheduleStep", jobRepository)
			.tasklet(trainDailyScheduleTasklet(null), transactionManager)
			.listener(TrainScheduleCacheContext.promotionListener())
			.build();
	}

	@Bean
	@StepScope
	public Tasklet trainDailyScheduleTasklet(
		@Value("#{jobParameters['operationDate']}") String operationDate
	) {
		return (contribution, chunkContext) -> {
			List<LocalDate> dates = (operationDate == null || operationDate.isBlank())
				? trainScheduleBatchFacade.createTrainSchedule()
				: trainScheduleBatchFacade.createTrainSchedule(List.of(LocalDate.parse(operationDate)));

			TrainScheduleCacheContext.putDatesFromToday(chunkContext, dates);
			return RepeatStatus.FINISHED;
		};
	}
}
