package com.sudo.raillo.batch.job;

import com.sudo.raillo.batch.application.facade.StationFareBatchFacade;
import com.sudo.raillo.batch.application.facade.TrainScheduleBatchFacade;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class TrainBatchJobConfig {

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final TrainScheduleBatchFacade trainScheduleBatchFacade;
	private final StationFareBatchFacade stationFareBatchFacade;

	@Bean
	public Job trainParseJob(@Qualifier("trainParseStep") Step trainParseStep) {
		return new JobBuilder("trainParseJob", jobRepository)
			.start(trainParseStep)
			.build();
	}

	@Bean
	public Job trainDailyScheduleJob(@Qualifier("trainDailyScheduleStep") Step trainDailyScheduleStep) {
		return new JobBuilder("trainDailyScheduleJob", jobRepository)
			.start(trainDailyScheduleStep)
			.build();
	}

	@Bean
	public Job trainMonthlyScheduleJob(@Qualifier("trainMonthlyScheduleStep") Step trainMonthlyScheduleStep) {
		return new JobBuilder("trainMonthlyScheduleJob", jobRepository)
			.start(trainMonthlyScheduleStep)
			.build();
	}

	@Bean
	public Job trainInitializeJob(
		@Qualifier("trainParseStep") Step trainParseStep,
		@Qualifier("trainMonthlyScheduleStep") Step trainMonthlyScheduleStep
	) {
		return new JobBuilder("trainInitializeJob", jobRepository)
			.start(trainParseStep)
			.next(trainMonthlyScheduleStep)
			.build();
	}

	@Bean
	public Step trainParseStep() {
		return new StepBuilder("trainParseStep", jobRepository)
			.tasklet(trainParseTasklet(), transactionManager)
			.build();
	}

	@Bean
	public Step trainDailyScheduleStep(
		@Qualifier("trainDailyScheduleTasklet") Tasklet trainDailyScheduleTasklet
	) {
		return new StepBuilder("trainDailyScheduleStep", jobRepository)
			.tasklet(trainDailyScheduleTasklet, transactionManager)
			.build();
	}

	@Bean
	public Step trainMonthlyScheduleStep() {
		return new StepBuilder("trainMonthlyScheduleStep", jobRepository)
			.tasklet(trainMonthlyScheduleTasklet(), transactionManager)
			.build();
	}

	@Bean
	public Tasklet trainParseTasklet() {
		return (contribution, chunkContext) -> {
			trainScheduleBatchFacade.parseTrainSchedule();
			stationFareBatchFacade.parseStationFare();
			return RepeatStatus.FINISHED;
		};
	}

	@Bean
	@StepScope
	public Tasklet trainDailyScheduleTasklet(
		@Value("#{jobParameters['operationDate']}") String operationDate
	) {
		return (contribution, chunkContext) -> {
			if (operationDate == null || operationDate.isBlank()) {
				trainScheduleBatchFacade.createTrainSchedule();
			} else {
				trainScheduleBatchFacade.createTrainSchedule(List.of(LocalDate.parse(operationDate)));
			}
			return RepeatStatus.FINISHED;
		};
	}

	@Bean
	public Tasklet trainMonthlyScheduleTasklet() {
		return (contribution, chunkContext) -> {
			LocalDate startDate = LocalDate.now();
			LocalDate endDate = startDate.plusMonths(1).plusDays(1);
			trainScheduleBatchFacade.createTrainSchedule(startDate.datesUntil(endDate).toList());
			return RepeatStatus.FINISHED;
		};
	}
}
