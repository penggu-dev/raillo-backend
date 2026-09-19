package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.sudo.raillo.batch.train.application.service.TrainScheduleCacheService;

@DisplayName("TrainScheduleCacheJobConfig - 적재 대상 날짜 해석")
class TrainScheduleCacheJobConfigTest {

	private final TrainScheduleCacheService trainScheduleCacheService = mock(TrainScheduleCacheService.class);
	private final TrainScheduleCacheJobConfig jobConfig =
		new TrainScheduleCacheJobConfig(mock(JobRepository.class), trainScheduleCacheService);

	@DisplayName("스케줄 생성 Step이 넘긴 날짜를 가장 먼저 쓴다")
	@Test
	void prefers_dates_promoted_from_previous_step() throws Exception {
		// given - 체인으로 실행되면 방금 만든 날짜 범위가 정답이다
		var tasklet = jobConfig.trainScheduleCacheTasklet(
			"2026-10-01", "2026-10-31", "2026-01-01", "2026-02-01", "2026-02-28");

		// when
		RepeatStatus status = tasklet.execute(null, null);

		// then
		assertThat(status).isEqualTo(RepeatStatus.FINISHED);
		verify(trainScheduleCacheService).load(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
	}

	@DisplayName("운행일 하나만 주면 그 하루만 적재한다")
	@Test
	void uses_single_operation_date() throws Exception {
		// given
		var tasklet = jobConfig.trainScheduleCacheTasklet(null, null, "2026-10-20", null, null);

		// when
		tasklet.execute(null, null);

		// then
		verify(trainScheduleCacheService).load(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20));
	}

	@DisplayName("시작일과 종료일로 범위를 지정할 수 있다")
	@Test
	void uses_explicit_date_range() throws Exception {
		// given - 캐시만 복구할 때 쓰는 단독 실행 경로
		var tasklet = jobConfig.trainScheduleCacheTasklet(null, null, null, "2026-10-01", "2026-10-31");

		// when
		tasklet.execute(null, null);

		// then
		verify(trainScheduleCacheService).load(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
	}

	@DisplayName("시작일이 종료일보다 늦으면 실패한다")
	@Test
	void fails_when_date_range_is_reversed() {
		// given - 아무것도 적재하지 않고 성공하면 복구가 된 것처럼 보인다
		var tasklet = jobConfig.trainScheduleCacheTasklet(null, null, null, "2026-10-31", "2026-10-01");

		// when

		// then
		assertThatThrownBy(() -> tasklet.execute(null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("시작일이 종료일보다 늦습니다");
	}

	@DisplayName("날짜를 하나도 알 수 없으면 실패한다")
	@Test
	void fails_when_no_date_is_given() {
		// given - 조용히 아무것도 안 하면 캐시가 빈 채로 성공한 것처럼 보인다
		var tasklet = jobConfig.trainScheduleCacheTasklet(null, null, null, null, null);

		// when

		// then
		assertThatThrownBy(() -> tasklet.execute(null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("operationDate");
	}

	@DisplayName("단독 실행할 수 있도록 Job 이름과 Step이 등록된다")
	@Test
	void registers_standalone_job() {
		// given

		// when
		Job job = jobConfig.trainScheduleCacheLoadJob();

		// then
		assertThat(job.getName()).isEqualTo(TrainScheduleCacheJobConfig.JOB_NAME);
		assertThat(((SimpleJob)job).getStepNames()).containsExactly("trainScheduleCacheStep");
	}
}
