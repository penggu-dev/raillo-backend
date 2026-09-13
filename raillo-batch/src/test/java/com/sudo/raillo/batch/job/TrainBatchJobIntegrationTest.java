package com.sudo.raillo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.batch.RailloBatchApplication;
import com.sudo.raillo.batch.infrastructure.StationRepository;
import com.sudo.raillo.batch.infrastructure.TrainRepository;
import com.sudo.raillo.batch.infrastructure.TrainScheduleTemplateRepository;
import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import com.sudo.raillo.train.domain.ScheduleStopTemplate;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainScheduleTemplate;
import com.sudo.raillo.train.domain.type.TrainType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
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
class TrainBatchJobIntegrationTest {

	private final JobOperatorTestUtils jobOperatorTestUtils;
	private final Job trainParseJob;
	private final Job trainDailyScheduleJob;
	private final Job trainMonthlyScheduleJob;
	private final JdbcTemplate jdbcTemplate;
	private final StationRepository stationRepository;
	private final TrainRepository trainRepository;
	private final TrainScheduleTemplateRepository trainScheduleTemplateRepository;

	@Autowired
	TrainBatchJobIntegrationTest(
		JobOperatorTestUtils jobOperatorTestUtils,
		@Qualifier("trainParseJob") Job trainParseJob,
		@Qualifier("trainDailyScheduleJob") Job trainDailyScheduleJob,
		@Qualifier("trainMonthlyScheduleJob") Job trainMonthlyScheduleJob,
		JdbcTemplate jdbcTemplate,
		StationRepository stationRepository,
		TrainRepository trainRepository,
		TrainScheduleTemplateRepository trainScheduleTemplateRepository
	) {
		this.jobOperatorTestUtils = jobOperatorTestUtils;
		this.trainParseJob = trainParseJob;
		this.trainDailyScheduleJob = trainDailyScheduleJob;
		this.trainMonthlyScheduleJob = trainMonthlyScheduleJob;
		this.jdbcTemplate = jdbcTemplate;
		this.stationRepository = stationRepository;
		this.trainRepository = trainRepository;
		this.trainScheduleTemplateRepository = trainScheduleTemplateRepository;
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM schedule_stop");
		jdbcTemplate.update("DELETE FROM train_schedule");
		jdbcTemplate.update("DELETE FROM schedule_stop_template");
		jdbcTemplate.update("DELETE FROM train_schedule_template");
		jdbcTemplate.update("DELETE FROM station_fare");
		jdbcTemplate.update("DELETE FROM seat");
		jdbcTemplate.update("DELETE FROM train_car");
		jdbcTemplate.update("DELETE FROM train");
		jdbcTemplate.update("DELETE FROM station");
	}

	@DisplayName("일일 Job을 같은 운행일로 다시 실행해도 스케줄을 중복 생성하지 않는다")
	@Test
	void daily_job_is_idempotent_for_same_operation_date() throws Exception {
		// given
		createScheduleTemplate();
		LocalDate operationDate = LocalDate.of(2026, 9, 15);

		// when
		BatchStatus firstStatus = runDailySchedule(operationDate);
		long firstCount = countSchedules(operationDate);
		BatchStatus secondStatus = runDailySchedule(operationDate);

		// then
		assertThat(firstStatus).isEqualTo(BatchStatus.COMPLETED);
		assertThat(secondStatus).isEqualTo(BatchStatus.COMPLETED);
		assertThat(firstCount).isPositive();
		assertThat(countSchedules(operationDate)).isEqualTo(firstCount);
	}

	@DisplayName("운행일 없는 일일 Job은 마지막 운행일의 다음 날짜를 생성한다")
	@Test
	void daily_job_creates_day_after_last_operation_date() throws Exception {
		// given
		createScheduleTemplate();
		LocalDate lastOperationDate = LocalDate.of(2026, 9, 15);
		runDailySchedule(lastOperationDate);
		jobOperatorTestUtils.setJob(trainDailyScheduleJob);

		// when
		JobExecution execution = jobOperatorTestUtils.startJob(uniqueParameters());

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(countSchedules(lastOperationDate.plusDays(1))).isPositive();
	}

	@DisplayName("월간 Job은 실행일부터 한 달 뒤까지 스케줄을 생성한다")
	@Test
	void monthly_job_creates_schedules_for_one_month() throws Exception {
		// given
		LocalDate beforeExecution = LocalDate.now();
		createScheduleTemplate();
		jobOperatorTestUtils.setJob(trainMonthlyScheduleJob);

		// when
		JobExecution execution = jobOperatorTestUtils.startJob(uniqueParameters());

		// then
		LocalDate afterExecution = LocalDate.now();
		LocalDate firstDate = jdbcTemplate.queryForObject(
			"SELECT MIN(operation_date) FROM train_schedule", LocalDate.class);
		LocalDate lastDate = jdbcTemplate.queryForObject(
			"SELECT MAX(operation_date) FROM train_schedule", LocalDate.class);

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(firstDate).isBetween(beforeExecution, afterExecution);
		assertThat(lastDate).isEqualTo(firstDate.plusMonths(1));
	}

	private void createScheduleTemplate() {
		Station departureStation = stationRepository.save(Station.create("서울"));
		Station arrivalStation = stationRepository.save(Station.create("부산"));
		Train train = trainRepository.save(Train.create(1, TrainType.KTX, "KTX", 20));
		ScheduleStopTemplate departureStop = ScheduleStopTemplate.create(
			0, null, java.time.LocalTime.of(6, 0), departureStation);
		ScheduleStopTemplate arrivalStop = ScheduleStopTemplate.create(
			1, java.time.LocalTime.of(8, 30), null, arrivalStation);
		trainScheduleTemplateRepository.saveAndFlush(TrainScheduleTemplate.create(
			"KTX 001 경부선",
			0b1111111,
			java.time.LocalTime.of(6, 0),
			java.time.LocalTime.of(8, 30),
			train,
			departureStation,
			arrivalStation,
			List.of(departureStop, arrivalStop)
		));
	}

	private BatchStatus runDailySchedule(LocalDate operationDate) throws Exception {
		jobOperatorTestUtils.setJob(trainDailyScheduleJob);
		return jobOperatorTestUtils.startJob(new JobParametersBuilder()
			.addString("operationDate", operationDate.toString())
			.addLong("run.id", System.nanoTime())
			.toJobParameters())
			.getStatus();
	}

	private org.springframework.batch.core.job.parameters.JobParameters uniqueParameters() {
		return new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();
	}

	private long countRows(String tableName) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
	}

	private long countSchedules(LocalDate operationDate) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM train_schedule WHERE operation_date = ?",
			Long.class,
			operationDate
		);
	}
}
