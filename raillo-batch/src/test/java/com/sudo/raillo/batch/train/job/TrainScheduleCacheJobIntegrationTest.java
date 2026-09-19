package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.sudo.raillo.batch.RailloBatchApplication;
import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import com.sudo.raillo.batch.train.infrastructure.StationRepository;
import com.sudo.raillo.batch.train.infrastructure.TrainRepository;
import com.sudo.raillo.batch.train.infrastructure.TrainScheduleTemplateRepository;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.domain.ScheduleStopTemplate;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainScheduleTemplate;
import com.sudo.raillo.train.domain.type.TrainType;

@ActiveProfiles("test")
@SpringBatchTest
@SpringBootTest(classes = RailloBatchApplication.class)
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
@DisplayName("운행 기준정보 적재")
class TrainScheduleCacheJobIntegrationTest {

	// 과거 날짜면 만료 시각이 이미 지나 키가 적재 직후 사라진다
	private static final LocalDate OPERATION_DATE = LocalDate.now(TrainCacheKey.ZONE).plusDays(7);
	private static final long TTL_TOLERANCE_SECONDS = 10L;

	private final JobOperatorTestUtils jobOperatorTestUtils;
	private final Job trainDailyScheduleJob;
	private final Job trainScheduleCacheLoadJob;
	private final JdbcTemplate jdbcTemplate;
	private final StringRedisTemplate stringRedisTemplate;
	private final StationRepository stationRepository;
	private final TrainRepository trainRepository;
	private final TrainScheduleTemplateRepository trainScheduleTemplateRepository;

	@Autowired
	TrainScheduleCacheJobIntegrationTest(
		JobOperatorTestUtils jobOperatorTestUtils,
		@Qualifier("trainDailyScheduleJob") Job trainDailyScheduleJob,
		@Qualifier("trainScheduleCacheLoadJob") Job trainScheduleCacheLoadJob,
		JdbcTemplate jdbcTemplate,
		StringRedisTemplate stringRedisTemplate,
		StationRepository stationRepository,
		TrainRepository trainRepository,
		TrainScheduleTemplateRepository trainScheduleTemplateRepository
	) {
		this.jobOperatorTestUtils = jobOperatorTestUtils;
		this.trainDailyScheduleJob = trainDailyScheduleJob;
		this.trainScheduleCacheLoadJob = trainScheduleCacheLoadJob;
		this.jdbcTemplate = jdbcTemplate;
		this.stringRedisTemplate = stringRedisTemplate;
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
		flushRedis();

		createScheduleTemplate();
	}

	@DisplayName("스케줄 생성 Job이 만든 운행이 곧바로 캐시에 담긴다")
	@Test
	void daily_job_caches_generated_schedule() throws Exception {
		// given

		// when
		BatchStatus status = runDailySchedule(OPERATION_DATE);

		// then
		assertThat(status).isEqualTo(BatchStatus.COMPLETED);
		assertThat(stringRedisTemplate.opsForValue().get(TrainCacheKey.scheduleInfo(scheduleId())))
			.contains("\"operationDate\":\"%s\"".formatted(OPERATION_DATE))
			.contains("\"departureTime\":\"06:00:00\"")
			.contains("\"operationStatus\":\"ACTIVE\"")
			.contains("\"trainNumber\":1")
			.contains("\"trainName\":\"KTX\"");
	}

	@DisplayName("정차역은 역 ID와 정차역 ID 어느 쪽으로도 찾을 수 있다")
	@Test
	void stop_is_reachable_from_both_directions() throws Exception {
		// given - 예약 생성은 역 ID로, 예약 조회는 저장해 둔 정차역 ID로 찾는다
		runDailySchedule(OPERATION_DATE);
		long trainScheduleId = scheduleId();
		long seoulId = stationId("서울");
		long seoulStopId = stopId(trainScheduleId, seoulId);

		// when
		Map<String, String> stops = stringRedisTemplate.<String, String>opsForHash()
			.entries(TrainCacheKey.scheduleStops(trainScheduleId));

		// then
		assertThat(stops.get(TrainCacheKey.stopFieldByStation(seoulId)))
			.isEqualTo(stops.get(TrainCacheKey.stopFieldByStopId(seoulStopId)))
			.contains("\"stationName\":\"서울\"");
	}

	@DisplayName("기점의 도착 시각은 null로 담긴다")
	@Test
	void origin_stop_has_null_arrival_time() throws Exception {
		// given
		runDailySchedule(OPERATION_DATE);

		// when
		String seoulStop = stringRedisTemplate.<String, String>opsForHash()
			.get(TrainCacheKey.scheduleStops(scheduleId()), TrainCacheKey.stopFieldByStation(stationId("서울")));

		// then
		assertThat(seoulStop)
			.contains("\"arrivalTime\":null")
			.contains("\"departureTime\":\"06:00:00\"");
	}

	@DisplayName("운행 키는 적재 시점이 아니라 운행일 기준으로 만료된다")
	@Test
	void schedule_keys_expire_based_on_operation_date() throws Exception {
		// given - 월간 Job이 한 달치를 미리 만들기 때문에 상대 TTL이면 운행일 전에 사라진다
		runDailySchedule(OPERATION_DATE);

		// when
		Long ttl = stringRedisTemplate.getExpire(
			TrainCacheKey.scheduleInfo(scheduleId()), TimeUnit.SECONDS);

		// then
		long expectedRemaining =
			TrainCacheKey.expireAtEpochSecond(OPERATION_DATE) - Instant.now().getEpochSecond();
		assertThat(ttl).isCloseTo(expectedRemaining, within(TTL_TOLERANCE_SECONDS));
	}

	@DisplayName("이미 스케줄이 있어 건너뛴 날짜도 캐시는 다시 채운다")
	@Test
	void refills_cache_even_when_schedule_generation_is_skipped() throws Exception {
		// given - 첫 실행으로 스케줄이 생긴 뒤 캐시만 비운 상황
		runDailySchedule(OPERATION_DATE);
		flushRedis();

		// when - DB는 건너뛰지만 캐시 적재 대상에는 남아야 한다
		BatchStatus status = runDailySchedule(OPERATION_DATE);

		// then
		assertThat(status).isEqualTo(BatchStatus.COMPLETED);
		assertThat(stringRedisTemplate.opsForValue().get(TrainCacheKey.scheduleInfo(scheduleId())))
			.isNotNull();
	}

	@DisplayName("단독 Job으로 날짜 범위를 지정해 캐시만 다시 채울 수 있다")
	@Test
	void standalone_job_refills_cache_for_date_range() throws Exception {
		// given
		runDailySchedule(OPERATION_DATE);
		flushRedis();

		// when
		jobOperatorTestUtils.setJob(trainScheduleCacheLoadJob);
		BatchStatus status = jobOperatorTestUtils.startJob(new JobParametersBuilder()
			.addString("fromDate", OPERATION_DATE.minusDays(1).toString())
			.addString("toDate", OPERATION_DATE.plusDays(1).toString())
			.addLong("run.id", System.nanoTime())
			.toJobParameters()).getStatus();

		// then
		assertThat(status).isEqualTo(BatchStatus.COMPLETED);
		assertThat(stringRedisTemplate.opsForValue().get(TrainCacheKey.scheduleInfo(scheduleId())))
			.isNotNull();
	}

	private BatchStatus runDailySchedule(LocalDate operationDate) throws Exception {
		jobOperatorTestUtils.setJob(trainDailyScheduleJob);
		return jobOperatorTestUtils.startJob(new JobParametersBuilder()
			.addString("operationDate", operationDate.toString())
			.addLong("run.id", System.nanoTime())
			.toJobParameters())
			.getStatus();
	}

	private void flushRedis() {
		stringRedisTemplate.execute((RedisCallback<Object>)connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	private long scheduleId() {
		return jdbcTemplate.queryForObject(
			"SELECT train_schedule_id FROM train_schedule WHERE operation_date = ?",
			Long.class, OPERATION_DATE);
	}

	private long stationId(String stationName) {
		return jdbcTemplate.queryForObject(
			"SELECT station_id FROM station WHERE station_name = ?", Long.class, stationName);
	}

	private long stopId(long trainScheduleId, long stationId) {
		return jdbcTemplate.queryForObject(
			"SELECT schedule_stop_id FROM schedule_stop WHERE train_schedule_id = ? AND station_id = ?",
			Long.class, trainScheduleId, stationId);
	}

	private void createScheduleTemplate() {
		Station departureStation = stationRepository.save(Station.create("서울"));
		Station arrivalStation = stationRepository.save(Station.create("부산"));
		Train train = trainRepository.save(Train.create(1, TrainType.KTX, "KTX", 20));

		trainScheduleTemplateRepository.saveAndFlush(TrainScheduleTemplate.create(
			"KTX 001 경부선",
			0b1111111,
			LocalTime.of(6, 0),
			LocalTime.of(8, 30),
			train,
			departureStation,
			arrivalStation,
			List.of(
				ScheduleStopTemplate.create(0, null, LocalTime.of(6, 0), departureStation),
				ScheduleStopTemplate.create(1, LocalTime.of(8, 30), null, arrivalStation)
			)
		));
	}
}
