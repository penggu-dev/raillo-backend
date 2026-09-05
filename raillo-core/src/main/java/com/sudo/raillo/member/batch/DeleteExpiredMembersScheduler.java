package com.sudo.raillo.member.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteExpiredMembersScheduler {

	private final JobOperator jobOperator;
	private final Job deleteExpiredMembersJob;

	@Scheduled(cron = "0 0 3 * * ?")
	public void runDeleteExpiredMembers() {
		try {
			log.info("삭제 배치 작업 시작");
			JobParameters jobParameters = new JobParametersBuilder()
				.addLong("time", System.currentTimeMillis()) // 작업 식별을 위한 시간 파라미터 추가
				.toJobParameters();

			jobOperator.start(deleteExpiredMembersJob, jobParameters);
			log.info("삭제 배치 작업 완료");

		} catch (JobExecutionAlreadyRunningException | JobRestartException
				 | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
			log.error("회원 영구 삭제 배치 작업 실행 중 오류 발생", e);
		}

	}
}
