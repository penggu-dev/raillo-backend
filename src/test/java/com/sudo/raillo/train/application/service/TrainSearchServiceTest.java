package com.sudo.raillo.train.application.service;

import static com.sudo.raillo.support.helper.TrainScheduleTestHelper.*;
import static com.sudo.raillo.train.exception.TrainErrorCode.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Train;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
class TrainSearchServiceTest {

	@Autowired
	private TrainSearchService trainSearchService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@DisplayName("기본 일정 조회 시 존재하지 않는 스케줄 ID 로 조회하면 상세 오류 코드와 메시지가 포함된 예외를 던진다")
	@Test
	void getTrainScheduleBasicInfo_throwsInformativeExceptionForNonExistentScheduleId() {
		// given
		Long nonExistentScheduleId = 999999L;

		// when & then
		assertThatThrownBy(() -> trainSearchService.getTrainScheduleBasicInfo(nonExistentScheduleId))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining(TRAIN_SCHEDULE_DETAIL_NOT_FOUND.getMessage());

		log.info("존재하지 않는 스케줄 ID({}) 조회 예외 처리 완료", nonExistentScheduleId);
	}

	/**
	 * 열차 스케줄 생성 헬퍼
	 */
	private TrainScheduleWithStopStations createTrainSchedule(Train train, LocalDate operationDate,
		String scheduleName, LocalTime departureTime, LocalTime arrivalTime,
		String departureStation, String arrivalStation) {
		return trainScheduleTestHelper.createCustomSchedule()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop(departureStation, null, departureTime)
			.addStop(arrivalStation, arrivalTime, null)
			.build();
	}
}
