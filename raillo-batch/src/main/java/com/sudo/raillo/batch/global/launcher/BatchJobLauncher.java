package com.sudo.raillo.batch.global.launcher;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * 명령행 옵션으로 지정한 Job 하나를 실행하고 결과를 프로세스 종료 코드로 돌려준다.
 *
 * <pre>
 * java -jar raillo-batch.jar --job=trainDailySchedule --operationDate=2026-10-20
 * </pre>
 *
 * <ul>
 *   <li>{@code --job}: 실행할 Job 이름</li>
 *   <li>점(.)이 없고 값이 있는 옵션: Job 파라미터로 전달</li>
 *   <li>{@code --spring.*}처럼 점이 있거나 값이 없는 옵션: Spring 설정으로 보고 Job 파라미터에서 제외</li>
 *   <li>{@code run.id}: 실행마다 자동으로 추가해 같은 Job을 반복 실행할 수 있게 한다</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = "batch.launcher.enabled", matchIfMissing = true)
public class BatchJobLauncher implements ApplicationRunner, ExitCodeGenerator {

	static final String JOB_OPTION = "job";
	static final String RUN_ID = "run.id";

	private final Map<String, Job> jobsByName;
	private final JobOperator jobOperator;
	private int exitCode;

	public BatchJobLauncher(List<Job> jobs, JobOperator jobOperator) {
		this.jobsByName = jobs.stream()
			.collect(Collectors.toMap(Job::getName, Function.identity()));
		this.jobOperator = jobOperator;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		Job job = resolveJob(args);
		JobParameters parameters = toJobParameters(args);

		log.info("[{}] Job 실행 시작 - parameters: {}", job.getName(), parameters);
		JobExecution execution = jobOperator.start(job, parameters);
		exitCode = execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
		log.info("[{}] Job 실행 종료 - status: {}", job.getName(), execution.getStatus());
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	private Job resolveJob(ApplicationArguments args) {
		List<String> values = args.getOptionValues(JOB_OPTION);
		if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException(
				"실행할 Job을 --job=<이름> 형식으로 하나 지정해야 합니다. 사용 가능한 Job: " + jobsByName.keySet());
		}

		String jobName = values.getFirst();
		Job job = jobsByName.get(jobName);
		if (job == null) {
			throw new IllegalArgumentException(
				"존재하지 않는 Job입니다: " + jobName + ". 사용 가능한 Job: " + jobsByName.keySet());
		}
		return job;
	}

	private JobParameters toJobParameters(ApplicationArguments args) {
		JobParametersBuilder builder = new JobParametersBuilder();
		for (String name : args.getOptionNames()) {
			List<String> values = args.getOptionValues(name);
			if (name.equals(JOB_OPTION) || name.contains(".") || values.isEmpty()) {
				continue;
			}
			builder.addString(name, values.getLast());
		}
		return builder
			.addLong(RUN_ID, System.currentTimeMillis())
			.toJobParameters();
	}
}
