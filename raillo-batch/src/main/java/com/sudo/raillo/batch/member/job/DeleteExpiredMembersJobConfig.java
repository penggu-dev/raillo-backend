package com.sudo.raillo.batch.member.job;

import com.sudo.raillo.batch.member.infrastructure.jdbc.ExpiredMemberIdReader;
import com.sudo.raillo.batch.member.infrastructure.jdbc.MemberJdbcRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 삭제 상태가 된 지 3년이 지난 회원을 100명 단위로 영구 삭제한다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DeleteExpiredMembersJobConfig {

	public static final String JOB_NAME = "deleteExpiredMembers";

	private static final int CHUNK_SIZE = 100;
	private static final int RETENTION_YEARS = 3;

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final DataSource dataSource;
	private final MemberJdbcRepository memberJdbcRepository;

	@Bean
	public Job deleteExpiredMembersJob() {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(deleteExpiredMembersStep())
			.build();
	}

	@Bean
	public Step deleteExpiredMembersStep() {
		return new StepBuilder("deleteExpiredMembersStep", jobRepository)
			.<Long, Long>chunk(CHUNK_SIZE)
			.transactionManager(transactionManager)
			.reader(expiredMembersReader())
			.writer(expiredMembersWriter())
			.build();
	}

	@Bean
	public JdbcPagingItemReader<Long> expiredMembersReader() {
		LocalDateTime deletedBefore = LocalDateTime.now().minusYears(RETENTION_YEARS);
		return new ExpiredMemberIdReader(dataSource, deletedBefore, CHUNK_SIZE);
	}

	@Bean
	public ItemWriter<Long> expiredMembersWriter() {
		return chunk -> {
			List<Long> memberIds = new ArrayList<>(chunk.getItems());
			if (memberIds.isEmpty()) {
				return;
			}

			log.info("총 {}명의 회원 영구 삭제 처리", memberIds.size());
			try {
				deleteRetryTemplate().execute(context -> {
					memberJdbcRepository.deleteAllByIds(memberIds);
					log.info("회원 영구 삭제 성공");
					return null;
				}, context -> {
					throw new RuntimeException("회원 삭제 실패: 최대 재시도 횟수 초과");
				});
			} catch (Exception e) {
				log.error("회원 영구 삭제 처리 중 오류 발생: {}", e.getMessage());
				throw e;
			}
		};
	}

	/**
	 * 최대 5회, 250ms부터 2배씩 늘려 최대 1초 간격으로 재시도한다.
	 */
	private RetryTemplate deleteRetryTemplate() {
		RetryTemplate retryTemplate = new RetryTemplate();
		retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5));

		ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
		backOffPolicy.setInitialInterval(250);
		backOffPolicy.setMultiplier(2);
		backOffPolicy.setMaxInterval(1000);
		retryTemplate.setBackOffPolicy(backOffPolicy);
		return retryTemplate;
	}
}
