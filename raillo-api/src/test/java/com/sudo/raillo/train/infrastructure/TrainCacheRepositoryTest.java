package com.sudo.raillo.train.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.support.annotation.RedisTest;
import com.sudo.raillo.train.application.dto.TrainCacheSnapshot;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.cache.TrainCarCacheValue;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;

@RedisTest
class TrainCacheRepositoryTest {

	private static final long SCHEDULE_ID = 1001L;
	private static final long SEOUL = 1L;
	private static final long BUSAN = 5L;

	@Autowired
	private TrainCacheRepository trainCacheRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private RedisJsonConverter redisJsonConverter;

	@BeforeEach
	void setUp() {
		ScheduleInfoCacheValue schedule = new ScheduleInfoCacheValue(
			SCHEDULE_ID, LocalDate.of(2026, 10, 20), LocalTime.of(6, 0), LocalTime.of(8, 52),
			OperationStatus.ACTIVE, 0, 7L, 101, "KTX", SEOUL, BUSAN);
		ScheduleStopCacheValue seoul = new ScheduleStopCacheValue(9001L, 0, SEOUL, "서울", null, LocalTime.of(6, 0));
		ScheduleStopCacheValue busan = new ScheduleStopCacheValue(9004L, 3, BUSAN, "부산", LocalTime.of(8, 52), null);

		stringRedisTemplate.opsForValue().set(TrainCacheKey.scheduleInfo(SCHEDULE_ID), redisJsonConverter.toJson(schedule));
		stringRedisTemplate.opsForHash().put(TrainCacheKey.scheduleStops(SCHEDULE_ID),
			TrainCacheKey.stopFieldByStation(SEOUL), redisJsonConverter.toJson(seoul));
		stringRedisTemplate.opsForHash().put(TrainCacheKey.scheduleStops(SCHEDULE_ID),
			TrainCacheKey.stopFieldByStation(BUSAN), redisJsonConverter.toJson(busan));
		stringRedisTemplate.opsForValue().set(TrainCacheKey.seat(12L), redisJsonConverter.toJson(
			new SeatCacheValue(231L, 3, CarType.STANDARD, 12, "A", SeatType.WINDOW)));
		stringRedisTemplate.opsForValue().set(TrainCacheKey.seat(13L), redisJsonConverter.toJson(
			new SeatCacheValue(231L, 3, CarType.STANDARD, 12, "B", SeatType.AISLE)));
		stringRedisTemplate.opsForHash().put(TrainCacheKey.fare(), TrainCacheKey.fareField(SEOUL, BUSAN), "59800:83700");
	}

	@Test
	@DisplayName("운행·정차역·좌석·운임을 한 번에 읽어 캐시 값 타입으로 돌려준다")
	void fetchesAllForReservation() {
		// given

		// when
		TrainCacheSnapshot snapshot = trainCacheRepository.fetchForReservation(SCHEDULE_ID, SEOUL, BUSAN, List.of(12L, 13L));

		// then
		assertThat(snapshot.schedule().trainNumber()).isEqualTo(101);
		assertThat(snapshot.schedule().operationDate()).isEqualTo(LocalDate.of(2026, 10, 20));
		assertThat(snapshot.departureStop().stopOrder()).isZero();
		assertThat(snapshot.departureStop().arrivalTime()).isNull();
		assertThat(snapshot.arrivalStop().stopOrder()).isEqualTo(3);
		assertThat(snapshot.seats()).extracting(SeatCacheValue::seatColumn).containsExactly("A", "B");
		assertThat(snapshot.fare()).isEqualTo(
			new StationFareCacheValue(new BigDecimal("59800"), new BigDecimal("83700")));
	}

	@Test
	@DisplayName("캐시에 없는 항목은 예외 없이 null 자리로 돌려준다")
	void returnsNullForMissingEntries() {
		// given
		long unknownSchedule = 9999L;

		// when
		TrainCacheSnapshot snapshot = trainCacheRepository.fetchForReservation(unknownSchedule, SEOUL, 77L, List.of(12L, 99L));

		// then
		assertThat(snapshot.schedule()).isNull();
		assertThat(snapshot.departureStop()).isNull();
		assertThat(snapshot.arrivalStop()).isNull();
		assertThat(snapshot.seats()).hasSize(2);
		assertThat(snapshot.seats().get(0)).isNotNull();
		assertThat(snapshot.seats().get(1)).isNull();
		assertThat(snapshot.fare()).isNull();
	}

	@Test
	@DisplayName("좌석 목록은 요청한 좌석 ID 순서를 그대로 따른다")
	void keepsSeatRequestOrder() {
		// given

		// when
		TrainCacheSnapshot snapshot = trainCacheRepository.fetchForReservation(SCHEDULE_ID, SEOUL, BUSAN, List.of(13L, 12L));

		// then
		assertThat(snapshot.seats()).extracting(SeatCacheValue::seatColumn).containsExactly("B", "A");
	}

	@Test
	@DisplayName("객차 정보를 한 번에 읽고 캐시에 없는 객차는 결과에서 뺀다")
	void fetch_train_cars_skips_missing() {
		// given
		stringRedisTemplate.opsForValue().set(TrainCacheKey.trainCar(231L), redisJsonConverter.toJson(
			new TrainCarCacheValue(7L, 3, CarType.STANDARD, 14, 56, "2+2")));

		// when
		Map<Long, TrainCarCacheValue> trainCars = trainCacheRepository.fetchTrainCars(List.of(231L, 999L));

		// then
		assertThat(trainCars).containsOnlyKeys(231L);
		assertThat(trainCars.get(231L).trainId()).isEqualTo(7L);
	}
}
