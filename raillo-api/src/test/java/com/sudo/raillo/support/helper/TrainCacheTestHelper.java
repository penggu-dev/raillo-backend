package com.sudo.raillo.support.helper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.support.repository.TestSeatRepository;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.cache.TrainCarCacheValue;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrainCacheTestHelper {

	private final StringRedisTemplate stringRedisTemplate;
	private final RedisJsonConverter redisJsonConverter;
	private final TestSeatRepository testSeatRepository;
	private final TrainCarRepository trainCarRepository;
	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final StationFareRepository stationFareRepository;

	/**
	 * 열차의 좌석·객차, 스케줄의 운행 정보·정차역·역, 그리고 DB에 있는 모든 구간 운임을 적재한다.
	 */
	@Transactional(readOnly = true)
	public void seed(Train train, TrainScheduleResult trainScheduleResult) {
		seedTrain(train);
		seedSchedule(trainScheduleResult.trainSchedule().getId());
		seedFares();
	}

	@Transactional(readOnly = true)
	public void seedTrain(Train train) {
		Map<String, String> values = new LinkedHashMap<>();

		for (Seat seat : testSeatRepository.findByTrainIdWithTrainCar(train.getId())) {
			TrainCar car = seat.getTrainCar();
			values.put(TrainCacheKey.seat(seat.getId()), redisJsonConverter.toJson(new SeatCacheValue(
				car.getId(), car.getCarNumber(), car.getCarType(),
				seat.getSeatRow(), seat.getSeatColumn(), seat.getSeatType())));
		}
		for (TrainCar car : trainCarRepository.findAllByTrainId(train.getId())) {
			values.put(TrainCacheKey.trainCar(car.getId()), redisJsonConverter.toJson(new TrainCarCacheValue(
				train.getId(), car.getCarNumber(), car.getCarType(),
				car.getSeatRowCount(), car.getTotalSeats(), car.getSeatArrangement())));
		}

		stringRedisTemplate.opsForValue().multiSet(values);
	}

	@Transactional(readOnly = true)
	public void seedSchedule(Long trainScheduleId) {
		TrainSchedule schedule = trainScheduleRepository.findById(trainScheduleId).orElseThrow();
		List<ScheduleStop> stops = scheduleStopRepository.findByTrainScheduleIdOrderByStopOrderAsc(trainScheduleId);
		long expireAt = TrainCacheKey.expireAtEpochSecond(schedule.getOperationDate());

		ScheduleInfoCacheValue info = new ScheduleInfoCacheValue(
			schedule.getId(), schedule.getOperationDate(), schedule.getDepartureTime(), schedule.getArrivalTime(),
			schedule.getOperationStatus(), schedule.getDelayMinutes(),
			schedule.getTrain().getId(), schedule.getTrain().getTrainNumber(), schedule.getTrain().getTrainName(),
			schedule.getDepartureStation().getId(), schedule.getArrivalStation().getId());

		Map<String, String> stopFields = new LinkedHashMap<>();
		Map<String, String> stationNames = new LinkedHashMap<>();
		for (ScheduleStop stop : stops) {
			String json = redisJsonConverter.toJson(new ScheduleStopCacheValue(
				stop.getId(), stop.getStopOrder(), stop.getStation().getId(), stop.getStation().getStationName(),
				stop.getArrivalTime(), stop.getDepartureTime()));
			stopFields.put(TrainCacheKey.stopFieldByStation(stop.getStation().getId()), json);
			stopFields.put(TrainCacheKey.stopFieldByStopId(stop.getId()), json);
			stationNames.put(TrainCacheKey.station(stop.getStation().getId()), stop.getStation().getStationName());
		}

		String infoKey = TrainCacheKey.scheduleInfo(trainScheduleId);
		String stopsKey = TrainCacheKey.scheduleStops(trainScheduleId);
		String infoJson = redisJsonConverter.toJson(info);

		stringRedisTemplate.executePipelined((RedisCallback<Object>)connection -> {
			connection.stringCommands().set(bytes(infoKey), bytes(infoJson));
			connection.keyCommands().expireAt(bytes(infoKey), expireAt);
			stopFields.forEach((field, json) -> connection.hashCommands().hSet(bytes(stopsKey), bytes(field), bytes(json)));
			connection.keyCommands().expireAt(bytes(stopsKey), expireAt);
			stationNames.forEach((key, name) -> connection.stringCommands().set(bytes(key), bytes(name)));
			return null;
		});
	}

	@Transactional(readOnly = true)
	public void seedFares() {
		Map<String, String> fields = new LinkedHashMap<>();
		for (StationFare fare : stationFareRepository.findAll()) {
			fields.put(
				TrainCacheKey.fareField(fare.getDepartureStation().getId(), fare.getArrivalStation().getId()),
				new StationFareCacheValue(fare.getStandardFare(), fare.getFirstClassFare()).serialize());
		}
		if (!fields.isEmpty()) {
			stringRedisTemplate.opsForHash().putAll(TrainCacheKey.fare(), fields);
		}
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
