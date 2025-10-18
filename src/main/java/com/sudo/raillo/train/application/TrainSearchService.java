package com.sudo.raillo.train.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.SeatReservationInfo;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.application.dto.TrainBasicInfo;
import com.sudo.raillo.train.application.dto.TrainScheduleBasicInfo;
import com.sudo.raillo.train.application.dto.projection.TrainSeatInfoBatch;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItem;
import com.sudo.raillo.train.application.dto.response.SeatTypeInfo;
import com.sudo.raillo.train.application.dto.response.StandingTypeInfo;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.application.validator.TrainSearchValidator;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.SeatReservationRepositoryCustom;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import com.sudo.raillo.train.infrastructure.StationRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepositoryCustom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 열차 검색 Service
 * 책임: 데이터 조회, 공휴일 판단 등 도메인 로직, 좌석 계산 로직
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class TrainSearchService {

	private static final int SEAT_BUFFER_THRESHOLD = 20; // 여유석 판단 기준

	private final TrainSearchValidator trainSearchValidator;
	private final TrainScheduleRepository trainScheduleRepository;
	private final TrainScheduleRepositoryCustom trainScheduleRepositoryCustom;
	private final StationFareRepository stationFareRepository;
	private final StationRepository stationRepository;
	private final SeatReservationRepositoryCustom seatReservationRepositoryCustom;

	/**
	 * 운행 캘린더 조회
	 * @return List<OperationCalendarItem>
	 */
	public List<OperationCalendarItem> getOperationCalendar() {
		LocalDate startDate = LocalDate.now();
		LocalDate endDate = startDate.plusMonths(1);

		// 운행 날짜 조회 (Set으로 반환)
		Set<LocalDate> datesWithSchedule = trainScheduleRepositoryCustom.findDatesWithActiveSchedules(startDate,
			endDate);

		List<OperationCalendarItem> calendar = startDate.datesUntil(endDate.plusDays(1))
			.map(date -> {
				boolean isHoliday = isHoliday(date);
				boolean hasSchedule = datesWithSchedule.contains(date);
				return OperationCalendarItem.create(date, isHoliday, hasSchedule);
			})
			.toList();

		log.info("운행 캘린더 조회 : {} ~ {} ({} 일), 운행일수: {}",
			startDate, endDate, calendar.size(), datesWithSchedule.size());

		return calendar;
	}

	/**
	 * 휴일 여부 판단
	 * TODO : 공휴일 API 연동 후 판단 처리 로직 추가 필요
	 */
	private boolean isHoliday(LocalDate date) {
		// 2025년 공휴일 임시 하드코딩
		Set<LocalDate> holidays2025 = Set.of(
			LocalDate.of(2025, 1, 1),   // 신정
			LocalDate.of(2025, 1, 28),  // 설날 연휴
			LocalDate.of(2025, 1, 29),  // 설날
			LocalDate.of(2025, 1, 30),  // 설날 연휴
			LocalDate.of(2025, 3, 1),   // 삼일절
			LocalDate.of(2025, 5, 5),   // 어린이날
			LocalDate.of(2025, 6, 6),   // 현충일
			LocalDate.of(2025, 8, 15),  // 광복절
			LocalDate.of(2025, 10, 3),  // 개천절
			LocalDate.of(2025, 10, 5),  // 추석 연휴
			LocalDate.of(2025, 10, 6),  // 추석 연휴
			LocalDate.of(2025, 10, 7),  // 추석 연휴
			LocalDate.of(2025, 10, 8),  // 추석
			LocalDate.of(2025, 10, 9),  // 한글날
			LocalDate.of(2025, 12, 25)  // 크리스마스
		);

		return holidays2025.contains(date);
	}

	/**
	 * 기본 열차 정보 조회
	 */
	public Slice<TrainBasicInfo> findTrainBasicInfo(TrainSearchRequest request, Pageable pageable) {
		LocalTime departureTimeFrom = request.getDepartureTimeFilter();

		Slice<TrainBasicInfo> trainPage = trainScheduleRepositoryCustom.findTrainBasicInfo(
			request.departureStationId(),
			request.arrivalStationId(),
			request.operationDate(),
			departureTimeFrom,
			pageable);

		return trainPage;
	}

	/**
	 * 구간별 요금 정보 조회
	 */
	public StationFare findStationFare(Long departureStationId, Long arrivalStationId) {
		log.debug("요금 정보 조회: {} -> {}", departureStationId, arrivalStationId);
		return stationFareRepository.findByDepartureStationIdAndArrivalStationId(departureStationId, arrivalStationId)
			.orElseThrow(() -> {
				log.error("요금 정보 없음: {} -> {}", departureStationId, arrivalStationId);
				return new BusinessException(TrainErrorCode.STATION_FARE_NOT_FOUND);
			});
	}

	/**
	 * 개별 열차 스케줄 기본 정보 조회
	 */
	public TrainScheduleBasicInfo getTrainScheduleBasicInfo(Long trainScheduleId) {
		TrainSchedule trainSchedule = trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_DETAIL_NOT_FOUND));

		return TrainScheduleBasicInfo.of(
			trainSchedule.getId(),
			trainSchedule.getTrain().getTrainType().getDescription(),
			String.format("%03d", trainSchedule.getTrain().getTrainNumber()),
			trainSchedule.getTrain().getTrainName()
		);
	}

	/**
	 * 열차 좌석 정보 배치 조회
	 */
	public TrainSeatInfoBatch findTrainSeatInfoBatch(List<Long> trainScheduleIds) {
		return trainScheduleRepositoryCustom.findTrainSeatInfoBatch(trainScheduleIds);
	}

	/**
	 * 겹치는 예약 배치 조회
	 */
	public Map<Long, List<SeatReservationInfo>> findOverlappingReservationsBatch(
		List<Long> trainScheduleIds, Long departureStationId, Long arrivalStationId) {
		return seatReservationRepositoryCustom.findOverlappingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId);
	}

	/**
	 * 입석 예약 수 배치 조회
	 */
	public Map<Long, Integer> countOverlappingStandingReservationsBatch(
		List<Long> trainScheduleIds, Long departureStationId, Long arrivalStationId) {
		return seatReservationRepositoryCustom.countOverlappingStandingReservationsBatch(
			trainScheduleIds, departureStationId, arrivalStationId);
	}

	// ============================================
	// Service Layer 전용 내부 Records
	// ============================================

	public record SeatCalculationResult(
		int standardRemaining, int standardTotal,
		int firstClassRemaining, int firstClassTotal
	) {
	}
}
