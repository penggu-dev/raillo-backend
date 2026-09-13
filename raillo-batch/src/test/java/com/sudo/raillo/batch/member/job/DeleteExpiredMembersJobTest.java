package com.sudo.raillo.batch.member.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.batch.RailloBatchApplication;
import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles("test")
@SpringBatchTest
@SpringBootTest(classes = RailloBatchApplication.class)
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
class DeleteExpiredMembersJobTest {

	private final JobOperatorTestUtils jobOperatorTestUtils;
	private final Job deleteExpiredMembersJob;
	private final JdbcTemplate jdbcTemplate;

	@Autowired
	DeleteExpiredMembersJobTest(
		JobOperatorTestUtils jobOperatorTestUtils,
		@Qualifier("deleteExpiredMembersJob") Job deleteExpiredMembersJob,
		JdbcTemplate jdbcTemplate
	) {
		this.jobOperatorTestUtils = jobOperatorTestUtils;
		this.deleteExpiredMembersJob = deleteExpiredMembersJob;
		this.jdbcTemplate = jdbcTemplate;
	}

	@BeforeEach
	void setUp() {
		jobOperatorTestUtils.setJob(deleteExpiredMembersJob);
		jdbcTemplate.update("DELETE FROM member");
	}

	@DisplayName("삭제된 지 3년이 지난 회원만 영구 삭제한다")
	@Test
	void deletes_only_members_expired_for_three_years() throws Exception {
		// given
		insertMember("expired", true, LocalDateTime.now().minusYears(3).minusDays(1));
		insertMember("recently-deleted", true, LocalDateTime.now().minusYears(2));
		insertMember("active", false, LocalDateTime.now().minusYears(4));

		var parameters = new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		// when
		var execution = jobOperatorTestUtils.startJob(parameters);

		// then
		assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
		assertThat(countMember("expired")).isZero();
		assertThat(countMember("recently-deleted")).isOne();
		assertThat(countMember("active")).isOne();
	}

	@DisplayName("만료 회원이 100명을 넘으면 여러 청크로 나누어 모두 삭제한다")
	@Test
	void deletes_expired_members_in_chunks_of_one_hundred() throws Exception {
		// given
		for (int index = 0; index < 101; index++) {
			insertMember("expired-" + index, true, LocalDateTime.now().minusYears(3).minusDays(1));
		}
		var parameters = new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		// when
		var execution = jobOperatorTestUtils.startJob(parameters);
		var stepExecution = execution.getStepExecutions().iterator().next();

		// then
		assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
		assertThat(stepExecution.getReadCount()).isEqualTo(101);
		assertThat(stepExecution.getWriteCount()).isEqualTo(101);
		assertThat(stepExecution.getCommitCount()).isEqualTo(2);
		assertThat(countAllMembers()).isZero();
	}

	private void insertMember(String name, boolean deleted, LocalDateTime updatedAt) {
		jdbcTemplate.update(
			"INSERT INTO member (created_at, updated_at, is_deleted, name, password, phone_number, role) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
			updatedAt,
			updatedAt,
			deleted,
			name,
			"password",
			"010-" + name,
			"MEMBER"
		);
	}

	private long countMember(String name) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM member WHERE name = ?",
			Long.class,
			name
		);
	}

	private long countAllMembers() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member", Long.class);
	}
}
