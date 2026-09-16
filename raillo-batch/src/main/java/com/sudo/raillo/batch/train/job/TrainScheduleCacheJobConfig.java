package com.sudo.raillo.batch.train.job;

import java.time.LocalDate;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.sudo.raillo.batch.train.application.service.TrainScheduleCacheService;

import lombok.RequiredArgsConstructor;

/**
 * 운행 정보와 정차역을 Redis에 적재
 */
@Configuration
@RequiredArgsConstructor
public class TrainScheduleCacheJobConfig {

	public static final String JOB_NAME = "trainScheduleCacheLoad";

	private final JobRepository jobRepository;
	private final TrainScheduleCacheService trainScheduleCacheService;

	@Bean
	public Job trainScheduleCacheLoadJob() {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(trainScheduleCacheStep())
			.build();
	}

	@Bean
	public Step trainScheduleCacheStep() {
		return new StepBuilder("trainScheduleCacheStep", jobRepository)
			.tasklet(trainScheduleCacheTasklet(null, null, null, null, null), new ResourcelessTransactionManager())
			.build();
	}

	/**
	 * 날짜는 스케줄 생성 Step이 넘긴 값, operationDate, fromDate·toDate 순으로 찾는다.
	 */
	@Bean
	@StepScope
	public Tasklet trainScheduleCacheTasklet(
		@Value("#{jobExecutionContext['trainCacheStartDate']}") String contextStartDate,
		@Value("#{jobExecutionContext['trainCacheEndDate']}") String contextEndDate,
		@Value("#{jobParameters['operationDate']}") String operationDate,
		@Value("#{jobParameters['fromDate']}") String fromDate,
		@Value("#{jobParameters['toDate']}") String toDate
	) {
		return (contribution, chunkContext) -> {
			DateRange range = resolveDateRange(contextStartDate, contextEndDate, operationDate, fromDate, toDate);
			trainScheduleCacheService.load(range.start(), range.end());
			return RepeatStatus.FINISHED;
		};
	}

	private static DateRange resolveDateRange(String contextStartDate, String contextEndDate,
		String operationDate, String fromDate, String toDate) {

		if (StringUtils.hasText(contextStartDate) && StringUtils.hasText(contextEndDate)) {
			return new DateRange(LocalDate.parse(contextStartDate), LocalDate.parse(contextEndDate));
		}
		if (StringUtils.hasText(operationDate)) {
			LocalDate date = LocalDate.parse(operationDate);
			return new DateRange(date, date);
		}
		if (StringUtils.hasText(fromDate) && StringUtils.hasText(toDate)) {
			return new DateRange(LocalDate.parse(fromDate), LocalDate.parse(toDate));
		}
		throw new IllegalArgumentException(
			"적재할 운행일을 찾을 수 없습니다. --operationDate 또는 --fromDate와 --toDate를 지정하세요.");
	}

	private record DateRange(LocalDate start, LocalDate end) {
	}
}
