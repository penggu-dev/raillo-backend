package com.sudo.raillo.batch.train.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
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
import com.sudo.raillo.batch.train.infrastructure.redis.TrainCacheRedisRepository;
import com.sudo.raillo.train.cache.TrainCacheKey;

@ActiveProfiles("test")
@SpringBatchTest
@SpringBootTest(classes = RailloBatchApplication.class)
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
@DisplayName("trainStaticCacheLoad - 정적 기준정보 적재")
class TrainStaticCacheJobIntegrationTest {

	private final JobOperatorTestUtils jobOperatorTestUtils;
	private final Job trainStaticCacheLoadJob;
	private final JdbcTemplate jdbcTemplate;
	private final StringRedisTemplate stringRedisTemplate;
	private final TrainCacheRedisRepository trainCacheRedisRepository;

	@Autowired
	TrainStaticCacheJobIntegrationTest(
		JobOperatorTestUtils jobOperatorTestUtils,
		@Qualifier("trainStaticCacheLoadJob") Job trainStaticCacheLoadJob,
		JdbcTemplate jdbcTemplate,
		StringRedisTemplate stringRedisTemplate,
		TrainCacheRedisRepository trainCacheRedisRepository
	) {
		this.jobOperatorTestUtils = jobOperatorTestUtils;
		this.trainStaticCacheLoadJob = trainStaticCacheLoadJob;
		this.jdbcTemplate = jdbcTemplate;
		this.stringRedisTemplate = stringRedisTemplate;
		this.trainCacheRedisRepository = trainCacheRedisRepository;
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

		stringRedisTemplate.execute((RedisCallback<Object>)connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	@DisplayName("DB의 좌석을 하나도 빠뜨리지 않고 적재한다")
	@Test
	void loads_every_seat_in_database() throws Exception {
		// given
		long stationId = insertStation("서울");
		long trainId = insertTrain(101, "KTX");
		long trainCarId = insertTrainCar(trainId, 3, "STANDARD");
		insertSeat(trainCarId, 12, "A", "WINDOW");
		insertSeat(trainCarId, 12, "B", "AISLE");
		insertStationFare(stationId, insertStation("부산"), "59800.00", "83700.00");

		// when
		BatchStatus status = runJob();

		// then
		assertThat(status).isEqualTo(BatchStatus.COMPLETED);
		assertThat(scanCount(TrainCacheKey.seatKeyPattern()))
			.isEqualTo(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat", Integer.class))
			.isEqualTo(2);
	}

	@DisplayName("좌석 값에 객차 번호와 등급이 함께 담긴다")
	@Test
	void seat_value_carries_denormalized_car_info() throws Exception {
		// given - 예약 생성은 좌석 ID만 알기 때문에 객차를 따로 조회하지 않게 한다
		long trainId = insertTrain(101, "KTX");
		long trainCarId = insertTrainCar(trainId, 3, "FIRST_CLASS");
		long seatId = insertSeat(trainCarId, 12, "A", "WINDOW");

		// when
		runJob();

		// then
		assertThat(stringRedisTemplate.opsForValue().get(TrainCacheKey.seat(seatId)))
			.isEqualTo("{\"trainCarId\":%d,\"carNumber\":3,\"carType\":\"FIRST_CLASS\","
				.formatted(trainCarId) + "\"seatRow\":12,\"seatColumn\":\"A\",\"seatType\":\"WINDOW\"}");
	}

	@DisplayName("역명은 JSON으로 감싸지 않고 문자열 그대로 담는다")
	@Test
	void station_is_stored_as_plain_name() throws Exception {
		// given
		long stationId = insertStation("서울");

		// when
		runJob();

		// then
		assertThat(stringRedisTemplate.opsForValue().get(TrainCacheKey.station(stationId)))
			.isEqualTo("서울");
	}

	@DisplayName("운임은 단일 Hash에 구간별 field로 담기고 DB 소수 자릿수를 떼어낸다")
	@Test
	void fare_is_stored_as_hash_field() throws Exception {
		// given
		long seoul = insertStation("서울");
		long busan = insertStation("부산");
		insertStationFare(seoul, busan, "59800.00", "83700.00");

		// when
		runJob();

		// then
		assertThat(stringRedisTemplate.<String, String>opsForHash()
			.get(TrainCacheKey.fare(), TrainCacheKey.fareField(seoul, busan)))
			.isEqualTo("59800:83700");
	}

	@DisplayName("정적 기준정보는 만료 시각을 갖지 않는다")
	@Test
	void static_keys_never_expire() throws Exception {
		// given
		long stationId = insertStation("서울");

		// when
		runJob();

		// then
		assertThat(stringRedisTemplate.getExpire(TrainCacheKey.station(stationId), TimeUnit.SECONDS))
			.isEqualTo(-1L);
	}

	@DisplayName("다시 실행해도 같은 결과가 된다")
	@Test
	void is_idempotent() throws Exception {
		// given
		long trainId = insertTrain(101, "KTX");
		long trainCarId = insertTrainCar(trainId, 3, "STANDARD");
		insertSeat(trainCarId, 12, "A", "WINDOW");

		// when
		runJob();
		int afterFirst = scanCount(TrainCacheKey.seatKeyPattern());
		runJob();

		// then
		assertThat(scanCount(TrainCacheKey.seatKeyPattern())).isEqualTo(afterFirst).isEqualTo(1);
	}

	private BatchStatus runJob() throws Exception {
		jobOperatorTestUtils.setJob(trainStaticCacheLoadJob);
		JobExecution execution = jobOperatorTestUtils.startJob();
		return execution.getStatus();
	}

	private int scanCount(String pattern) {
		return trainCacheRedisRepository.scanKeys(pattern).size();
	}

	private long insertStation(String stationName) {
		jdbcTemplate.update("INSERT INTO station (station_name) VALUES (?)", stationName);
		return jdbcTemplate.queryForObject(
			"SELECT station_id FROM station WHERE station_name = ?", Long.class, stationName);
	}

	private long insertTrain(int trainNumber, String trainName) {
		jdbcTemplate.update(
			"INSERT INTO train (train_number, train_type, train_name, total_cars) VALUES (?, 'KTX', ?, 1)",
			trainNumber, trainName);
		return jdbcTemplate.queryForObject(
			"SELECT train_id FROM train WHERE train_number = ?", Long.class, trainNumber);
	}

	private long insertTrainCar(long trainId, int carNumber, String carType) {
		jdbcTemplate.update("""
			INSERT INTO train_car (car_number, car_type, seat_row_count, total_seats, seat_arrangement, train_id)
			VALUES (?, ?, 15, 60, '2+2', ?)
			""", carNumber, carType, trainId);
		return jdbcTemplate.queryForObject(
			"SELECT train_car_id FROM train_car WHERE train_id = ? AND car_number = ?",
			Long.class, trainId, carNumber);
	}

	private long insertSeat(long trainCarId, int seatRow, String seatColumn, String seatType) {
		jdbcTemplate.update("""
			INSERT INTO seat (seat_row, seat_column, seat_type, train_car_id)
			VALUES (?, ?, ?, ?)
			""", seatRow, seatColumn, seatType, trainCarId);
		return jdbcTemplate.queryForObject("""
			SELECT seat_id FROM seat WHERE train_car_id = ? AND seat_row = ? AND seat_column = ?
			""", Long.class, trainCarId, seatRow, seatColumn);
	}

	private void insertStationFare(long departureStationId, long arrivalStationId,
		String standardFare, String firstClassFare) {
		jdbcTemplate.update("""
			INSERT INTO station_fare (departure_station_id, arrival_station_id, standard_fare, first_class_fare)
			VALUES (?, ?, ?, ?)
			""", departureStationId, arrivalStationId, standardFare, firstClassFare);
	}
}
