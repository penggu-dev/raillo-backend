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
		// 2026년 공휴일 임시 하드코딩
		Set<LocalDate> holidays2026 = Set.of(
			LocalDate.of(2026, 1, 1),   // 신정
			LocalDate.of(2026, 2, 16),  // 설날 연휴
			LocalDate.of(2026, 2, 17),  // 설날
			LocalDate.of(2026, 2, 18),  // 설날 연휴
			LocalDate.of(2026, 3, 1),   // 삼일절
			LocalDate.of(2026, 3, 2),   // 삼일절 대체공휴일
			LocalDate.of(2026, 5, 5),   // 어린이날
			LocalDate.of(2026, 5, 24),  // 부처님오신날
			LocalDate.of(2026, 5, 25),  // 부처님오신날 대체공휴일
			LocalDate.of(2026, 6, 6),   // 현충일
			LocalDate.of(2026, 8, 15),  // 광복절
			LocalDate.of(2026, 8, 17),  // 광복절 대체공휴일
			LocalDate.of(2026, 9, 24),  // 추석 연휴
			LocalDate.of(2026, 9, 25),  // 추석
			LocalDate.of(2026, 9, 26),  // 추석 연휴
			LocalDate.of(2026, 10, 3),  // 개천절
			LocalDate.of(2026, 10, 5),  // 개천절 대체공휴일
			LocalDate.of(2026, 10, 9),  // 한글날
			LocalDate.of(2026, 12, 25)  // 크리스마스
		);

		return holidays2026.contains(date);
	}
}
