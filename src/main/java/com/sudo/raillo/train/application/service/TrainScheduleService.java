package com.sudo.raillo.train.application.service;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 열차 스케줄 기본 정보 조회 Service
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainScheduleService {

	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;

	public TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	public ScheduleStop getStopStation(TrainSchedule trainSchedule, Long stationId) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), stationId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}
}
