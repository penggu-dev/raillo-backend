package com.sudo.raillo.batch.train.job;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sudo.raillo.batch.train.application.service.TrainStaticCacheService;

import lombok.RequiredArgsConstructor;

/**
 * 좌석·객차·역·운임을 Redis에 적재
 */
@Configuration
@RequiredArgsConstructor
public class TrainStaticCacheJobConfig {

	public static final String JOB_NAME = "trainStaticCacheLoad";

	private final JobRepository jobRepository;
	private final TrainStaticCacheService trainStaticCacheService;

	@Bean
	public Job trainStaticCacheLoadJob() {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainStaticCacheStep())
			.build();
	}

	@Bean
	public Step trainStaticCacheStep() {
		return new StepBuilder("trainStaticCacheStep", jobRepository)
			.tasklet(trainStaticCacheTasklet(), new ResourcelessTransactionManager())
			.build();
	}

	@Bean
	public Tasklet trainStaticCacheTasklet() {
		return (contribution, chunkContext) -> {
			trainStaticCacheService.loadAll();
			return RepeatStatus.FINISHED;
		};
	}
}
