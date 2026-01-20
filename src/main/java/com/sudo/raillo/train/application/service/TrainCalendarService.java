package com.sudo.raillo.train.application.service;

import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import com.sudo.raillo.train.infrastructure.TrainScheduleQueryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 열차 운행 캘린더 Service
 * 운행 캘린더 조회 및 공휴일 판단
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainCalendarService {

	private final TrainScheduleQueryRepository trainScheduleQueryRepository;

	/**
	 * 운행 캘린더 조회
	 * @return List<OperationCalendarItem>
	 */
	public List<OperationCalendarItemResponse> getOperationCalendar() {
		LocalDate startDate = LocalDate.now();
		LocalDate endDate = startDate.plusMonths(1);

		// 운행 날짜 조회 (Set으로 반환)
		Set<LocalDate> datesWithSchedule = trainScheduleQueryRepository.findDatesWithActiveSchedules(startDate,
			endDate);

		List<OperationCalendarItemResponse> calendar = startDate.datesUntil(endDate.plusDays(1))
			.map(date -> {
				boolean isHoliday = isHoliday(date);
				boolean hasSchedule = datesWithSchedule.contains(date);
				return OperationCalendarItemResponse.of(date, isHoliday, hasSchedule);
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
}
