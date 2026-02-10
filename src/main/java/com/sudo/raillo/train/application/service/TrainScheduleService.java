package com.sudo.raillo.train.application.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.TrainScheduleTimeInfo;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;

/**
 * 열차 스케줄 기본 정보 조회 Service
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainScheduleService {

	private static final String TRAIN_SCHEDULE_TIME_INFO_CACHE = "trainScheduleTimeInfo";

	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;

	public TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	/**
	 * TTL 계산용 열차 스케줄 시간 정보 조회 (캐시 적용)
	 */
	@Cacheable(value = TRAIN_SCHEDULE_TIME_INFO_CACHE, key = "#trainScheduleId")
	public TrainScheduleTimeInfo getTrainScheduleTimeInfo(Long trainScheduleId) {
		TrainSchedule trainSchedule = trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
		return TrainScheduleTimeInfo.from(trainSchedule);
	}

	public ScheduleStop getStopStation(TrainSchedule trainSchedule, Long stationId) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), stationId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}
}
