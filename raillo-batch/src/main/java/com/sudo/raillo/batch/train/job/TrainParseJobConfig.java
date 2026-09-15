package com.sudo.raillo.batch.train.job;

import com.sudo.raillo.batch.train.application.facade.StationFareBatchFacade;
import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 시간표·운임 Excel을 파싱해 역, 열차, 스케줄 템플릿, 운임을 저장한다.
 */
@Configuration
@RequiredArgsConstructor
public class TrainParseJobConfig {

	public static final String JOB_NAME = "trainParse";

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final TrainScheduleBatchFacade trainScheduleBatchFacade;
	private final StationFareBatchFacade stationFareBatchFacade;

	@Bean
	public Job trainParseJob() {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainParseStep())
			.build();
	}

	@Bean
	public Step trainParseStep() {
		return new StepBuilder("trainParseStep", jobRepository)
			.tasklet(trainParseTasklet(), transactionManager)
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
}
