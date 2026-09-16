package com.sudo.raillo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.batch.member.job.DeleteExpiredMembersJobConfig;
import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import com.sudo.raillo.batch.train.job.TrainDailyScheduleJobConfig;
import com.sudo.raillo.batch.train.job.TrainInitializeJobConfig;
import com.sudo.raillo.batch.train.job.TrainMonthlyScheduleJobConfig;
import com.sudo.raillo.batch.train.job.TrainParseJobConfig;
import com.sudo.raillo.batch.train.job.TrainStaticCacheJobConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
class RailloBatchApplicationTests {

	@DisplayName("배치 애플리케이션이 웹 서버 없이 시작된다")
	@Test
	void context_loads_without_web_server(ApplicationContext context) {
		// given

		// when
		String webApplicationType = context.getEnvironment()
			.getProperty("spring.main.web-application-type");

		// then
		assertThat(webApplicationType).isEqualTo("none");
		assertThat(context.getClass().getName()).doesNotContain("WebServer");
	}

	@DisplayName("배치 데이터소스는 JDBC 배치 INSERT를 한 문장으로 재작성하는 MySQL 옵션을 사용한다")
	@Test
	void datasource_enables_rewrite_batched_statements(ApplicationContext context) {
		// given
		HikariDataSource dataSource = context.getBean(HikariDataSource.class);

		// when
		String rewriteBatchedStatements = dataSource.getDataSourceProperties()
			.getProperty("rewriteBatchedStatements");

		// then
		assertThat(rewriteBatchedStatements).isEqualTo("true");
	}

	@DisplayName("기준정보 적재에 쓸 Redis 연결이 동작한다")
	@Test
	void redis_connection_is_available(ApplicationContext context) {
		// given
		StringRedisTemplate redisTemplate = context.getBean(StringRedisTemplate.class);

		// when
		String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);

		// then
		assertThat(pong).isEqualTo("PONG");
	}

	@DisplayName("--job 옵션으로 실행할 수 있는 Job이 모두 등록된다")
	@Test
	void registers_all_jobs_by_command_line_name(ApplicationContext context) {
		// given

		// when
		var jobNames = context.getBeansOfType(Job.class).values().stream()
			.map(Job::getName)
			.toList();

		// then
		assertThat(jobNames).containsExactlyInAnyOrder(
			TrainParseJobConfig.JOB_NAME,
			TrainStaticCacheJobConfig.JOB_NAME,
			TrainDailyScheduleJobConfig.JOB_NAME,
			TrainMonthlyScheduleJobConfig.JOB_NAME,
			TrainInitializeJobConfig.JOB_NAME,
			DeleteExpiredMembersJobConfig.JOB_NAME
		);
	}

}
