package com.sudo.raillo.train.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.response.OperationCalendarItem;
import com.sudo.raillo.train.domain.Train;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class TrainCalendarServiceTest {

	@Autowired
	private TrainCalendarService trainCalendarService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Test
	@DisplayName("운행 캘린더는 금일부터 1개월 기간을 조회한다")
	void shouldReturnOneMonthCalendar() {
		// given
		LocalDate today = LocalDate.now();
		LocalDate endDate = today.plusMonths(1);

		// when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		assertThat(calendar).hasSizeGreaterThanOrEqualTo(28).hasSizeLessThanOrEqualTo(32);
		assertThat(calendar.get(0).operationDate()).isEqualTo(today);
		assertThat(calendar.get(calendar.size() - 1).operationDate()).isEqualTo(endDate);
	}

	@Test
	@DisplayName("운행하는 날짜는 'Y', 운행하지 않는 날짜는 'N'으로 표시된다")
	void shouldMarkOperatingDays() {
		// given
		Train train = trainTestHelper.createKTX();
		LocalDate today = LocalDate.now();
		LocalDate tomorrow = today.plusDays(1);
		LocalDate dayAfter = today.plusDays(2);

		createTrainSchedule(train, today, "KTX 001", LocalTime.of(10, 0), LocalTime.of(13, 0));
		createTrainSchedule(train, dayAfter, "KTX 002", LocalTime.of(14, 0), LocalTime.of(17, 0));

		// when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		OperationCalendarItem todayItem = findByDate(calendar, today);
		OperationCalendarItem tomorrowItem = findByDate(calendar, tomorrow);
		OperationCalendarItem dayAfterItem = findByDate(calendar, dayAfter);

		assertThat(todayItem.isBookingAvailable()).isEqualTo("Y");
		assertThat(tomorrowItem.isBookingAvailable()).isEqualTo("N");
		assertThat(dayAfterItem.isBookingAvailable()).isEqualTo("Y");
	}

	@Test
	@DisplayName("캘린더의 모든 날짜는 공휴일 여부 정보를 가진다")
	void shouldHaveHolidayStatusForAllDates() {
		// given & when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		assertThat(calendar).isNotEmpty();
		assertThat(calendar).allMatch(item ->
			item.isHoliday() != null &&
				(item.isHoliday().equals("Y") || item.isHoliday().equals("N"))
		);
	}

	@Test
	@DisplayName("공휴일이 아닌 평일은 'N'으로 표시된다")
	void shouldMarkRegularDays() {
		// given & when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		long regularDaysCount = calendar.stream()
			.filter(item -> item.isHoliday().equals("N"))
			.count();

		assertThat(regularDaysCount).isGreaterThan(0);
	}

	@Test
	@DisplayName("공휴일에도 운행이 가능하다")
	void shouldAllowOperationOnHolidays() {
		// given
		if (LocalDate.now().getYear() != 2025 || LocalDate.now().getMonth().getValue() != 1) {
			return; // 2025년 1월이 아니면 스킵
		}

		Train train = trainTestHelper.createKTX();
		LocalDate newYear = LocalDate.of(2025, 1, 1);

		createTrainSchedule(train, newYear, "KTX 001", LocalTime.of(10, 0), LocalTime.of(13, 0));

		// when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		OperationCalendarItem newYearItem = findByDate(calendar, newYear);
		assertThat(newYearItem.isHoliday()).isEqualTo("Y");
		assertThat(newYearItem.isBookingAvailable()).isEqualTo("Y");
	}

	@Test
	@DisplayName("운행하는 날이 없어도 캘린더는 정상적으로 조회된다")
	void shouldHandleEmptySchedule() {
		// given & when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		assertThat(calendar).isNotEmpty();
	}

	@Test
	@DisplayName("여러 날짜에 운행 스케줄이 있으면 모두 운행 가능으로 표시된다")
	void shouldMarkMultipleOperatingDaysAsAvailable() {
		// given
		Train train = trainTestHelper.createKTX();
		LocalDate today = LocalDate.now();

		for (int i = 0; i < 5; i++) {
			LocalDate date = today.plusDays(i);
			createTrainSchedule(train, date, "KTX " + (i + 1),
				LocalTime.of(10, 0), LocalTime.of(13, 0));
		}

		// when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		for (int i = 0; i < 5; i++) {
			LocalDate date = today.plusDays(i);
			OperationCalendarItem item = findByDate(calendar, date);
			assertThat(item.isBookingAvailable()).as("%s는 운행", date).isEqualTo("Y");
		}
	}

	@Test
	@DisplayName("운행하는 날짜 수를 정확하게 계산한다")
	void shouldCountOperatingDays() {
		// given
		Train train = trainTestHelper.createKTX();
		LocalDate today = LocalDate.now();

		createTrainSchedule(train, today, "KTX 001", LocalTime.of(10, 0), LocalTime.of(13, 0));
		createTrainSchedule(train, today.plusDays(3), "KTX 002", LocalTime.of(14, 0), LocalTime.of(17, 0));
		createTrainSchedule(train, today.plusDays(7), "KTX 003", LocalTime.of(9, 0), LocalTime.of(12, 0));

		// when
		List<OperationCalendarItem> calendar = trainCalendarService.getOperationCalendar();

		// then
		long operatingDaysCount = calendar.stream()
			.filter(item -> item.isBookingAvailable().equals("Y"))
			.count();

		assertThat(operatingDaysCount).isEqualTo(3);
	}

	private OperationCalendarItem findByDate(List<OperationCalendarItem> calendar, LocalDate date) {
		return calendar.stream()
			.filter(item -> item.operationDate().equals(date))
			.findFirst()
			.orElseThrow(() -> new AssertionError("날짜 " + date + "를 찾을 수 없습니다"));
	}

	private TrainScheduleResult createTrainSchedule(Train train, LocalDate operationDate,
                                                    String scheduleName, LocalTime departureTime, LocalTime arrivalTime) {
		return trainScheduleTestHelper.createCustomSchedule()
			.scheduleName(scheduleName)
			.operationDate(operationDate)
			.train(train)
			.addStop("서울", null, departureTime)
			.addStop("부산", arrivalTime, null)
			.build();
	}
}
