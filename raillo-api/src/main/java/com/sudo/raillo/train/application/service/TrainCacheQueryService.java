package com.sudo.raillo.train.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.train.application.dto.ReservationTrainContext;
import com.sudo.raillo.train.application.dto.TrainCacheSnapshot;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.TrainCarCacheValue;
import com.sudo.raillo.train.exception.TrainError;
import com.sudo.raillo.train.infrastructure.TrainCacheRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainCacheQueryService {

	private final TrainCacheRepository trainCacheRepository;

	/**
	 * 예약 생성에 필요한 기준정보를 읽고 하나라도 없으면 예외를 던진다.
	 */
	public ReservationTrainContext getReservationContext(
		long trainScheduleId,
		long departureStationId,
		long arrivalStationId,
		List<Long> seatIds
	) {
		TrainCacheSnapshot snapshot = trainCacheRepository.fetchForReservation(
			trainScheduleId, departureStationId, arrivalStationId, seatIds);

		if (snapshot.schedule() == null) {
			log.warn("[운행 캐시 없음] trainScheduleId={}", trainScheduleId);
			throw new BusinessException(TrainError.TRAIN_SCHEDULE_NOT_FOUND);
		}
		if (snapshot.departureStop() == null || snapshot.arrivalStop() == null) {
			log.warn("[정차역 캐시 없음] trainScheduleId={}, departureStationId={}, arrivalStationId={}",
				trainScheduleId, departureStationId, arrivalStationId);
			throw new BusinessException(TrainError.STATION_NOT_FOUND);
		}
		Map<Long, SeatCacheValue> seatsById = toSeatsById(seatIds, snapshot.seats());
		requireSeatsOnTrain(snapshot.schedule(), seatsById);

		return new ReservationTrainContext(
			snapshot.schedule(),
			snapshot.departureStop(),
			snapshot.arrivalStop(),
			seatsById,
			snapshot.fare()
		);
	}

	/** 좌석이 모두 요청 운행의 열차에 속해야 한다. 다른 열차의 좌석은 이 운행에서 찾을 수 없는 좌석으로 본다. */
	private void requireSeatsOnTrain(ScheduleInfoCacheValue schedule, Map<Long, SeatCacheValue> seatsById) {
		Set<Long> trainCarIds = seatsById.values().stream()
			.map(SeatCacheValue::trainCarId)
			.collect(Collectors.toSet());
		Map<Long, TrainCarCacheValue> trainCars = trainCacheRepository.fetchTrainCars(trainCarIds);

		for (Long trainCarId : trainCarIds) {
			TrainCarCacheValue trainCar = trainCars.get(trainCarId);
			if (trainCar == null) {
				log.warn("[객차 캐시 없음] trainCarId={}", trainCarId);
				throw new BusinessException(TrainError.TRAIN_CAR_NOT_FOUND);
			}
			if (trainCar.trainId() != schedule.trainId()) {
				log.warn("[다른 열차의 좌석] trainScheduleId={}, trainId={}, trainCarId={}, seatTrainId={}",
					schedule.trainScheduleId(), schedule.trainId(), trainCarId, trainCar.trainId());
				throw new BusinessException(TrainError.SEAT_NOT_FOUND);
			}
		}
	}

	private Map<Long, SeatCacheValue> toSeatsById(List<Long> seatIds, List<SeatCacheValue> seats) {
		Map<Long, SeatCacheValue> seatsById = new LinkedHashMap<>();
		for (int i = 0; i < seatIds.size(); i++) {
			if (seats.get(i) == null) {
				log.warn("[좌석 캐시 없음] seatId={}", seatIds.get(i));
				throw new BusinessException(TrainError.SEAT_NOT_FOUND);
			}
			seatsById.put(seatIds.get(i), seats.get(i));
		}
		return seatsById;
	}
}
