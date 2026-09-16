package com.sudo.raillo.batch.train.job;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 빈 DB 초기 구성용. 파싱 → 정적 기준정보 적재 → 한 달치 스케줄 생성 순으로 실행한다.
 */
@Configuration
public class TrainInitializeJobConfig {

	public static final String JOB_NAME = "trainInitialize";

	@Bean
	public Job trainInitializeJob(
		JobRepository jobRepository,
		@Qualifier("trainParseStep") Step trainParseStep,
		@Qualifier("trainStaticCacheStep") Step trainStaticCacheStep,
		@Qualifier("trainMonthlyScheduleStep") Step trainMonthlyScheduleStep
	) {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainParseStep)
			.next(trainStaticCacheStep)
			.next(trainMonthlyScheduleStep)
			.build();
	}
}
