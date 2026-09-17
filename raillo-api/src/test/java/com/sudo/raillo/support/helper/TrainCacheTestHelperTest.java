package com.sudo.raillo.support.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class TrainCacheTestHelperTest {

	@Autowired
	private TrainCacheTestHelper trainCacheTestHelper;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private RedisJsonConverter redisJsonConverter;

	@Test
	@DisplayName("적재한 값은 Batch와 같은 키·형식이라 기준정보 캐시 타입으로 다시 읽힌다")
	void seedsInBatchFormat() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);
		Long scheduleId = result.trainSchedule().getId();
		Seat seat = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1).get(0);
		ScheduleStop seoul = result.scheduleStops().get(0);

		// when
		trainCacheTestHelper.seed(train, result);

		// then
		String seatJson = stringRedisTemplate.opsForValue().get(TrainCacheKey.seat(seat.getId()));
		SeatCacheValue seatValue = redisJsonConverter.fromJson(seatJson, SeatCacheValue.class);
		assertThat(seatJson).doesNotContain("@class");
		assertThat(seatValue.carType()).isEqualTo(CarType.FIRST_CLASS);
		assertThat(seatValue.trainCarId()).isEqualTo(seat.getTrainCar().getId());

		String infoJson = stringRedisTemplate.opsForValue().get(TrainCacheKey.scheduleInfo(scheduleId));
		ScheduleInfoCacheValue info = redisJsonConverter.fromJson(infoJson, ScheduleInfoCacheValue.class);
		assertThat(infoJson).contains("\"departureTime\":\"05:00:00\"");
		assertThat(info.trainNumber()).isEqualTo(train.getTrainNumber());

		Object stopJson = stringRedisTemplate.opsForHash().get(TrainCacheKey.scheduleStops(scheduleId),
			TrainCacheKey.stopFieldByStation(seoul.getStation().getId()));
		ScheduleStopCacheValue stop = redisJsonConverter.fromJson((String)stopJson, ScheduleStopCacheValue.class);
		assertThat(stop.stopId()).isEqualTo(seoul.getId());
		assertThat(stop.stationName()).isEqualTo("서울");
		assertThat(stringRedisTemplate.opsForHash().hasKey(TrainCacheKey.scheduleStops(scheduleId),
			TrainCacheKey.stopFieldByStopId(seoul.getId()))).isTrue();

		Object fare = stringRedisTemplate.opsForHash().get(TrainCacheKey.fare(),
			TrainCacheKey.fareField(seoul.getStation().getId(), result.scheduleStops().get(1).getStation().getId()));
		assertThat(fare).isEqualTo("50000:100000");
	}

	@Test
	@DisplayName("운행 키에는 운행일 기준 만료가 걸리고 정적 키에는 만료가 없다")
	void expiresOnlyScheduleKeys() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);
		Long scheduleId = result.trainSchedule().getId();
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		// when
		trainCacheTestHelper.seed(train, result);

		// then
		assertThat(stringRedisTemplate.getExpire(TrainCacheKey.scheduleInfo(scheduleId), TimeUnit.SECONDS)).isPositive();
		assertThat(stringRedisTemplate.getExpire(TrainCacheKey.scheduleStops(scheduleId), TimeUnit.SECONDS)).isPositive();
		assertThat(stringRedisTemplate.getExpire(TrainCacheKey.seat(seats.get(0).getId()), TimeUnit.SECONDS)).isEqualTo(-1);
		assertThat(stringRedisTemplate.getExpire(TrainCacheKey.fare(), TimeUnit.SECONDS)).isEqualTo(-1);
	}
}
