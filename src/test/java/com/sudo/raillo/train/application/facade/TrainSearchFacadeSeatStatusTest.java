package com.sudo.raillo.train.application.facade;

import static com.sudo.raillo.support.helper.TrainScheduleTestHelper.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatAvailabilityStatus;

import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
@DisplayName("좌석 상태 관련 TrainSearchFacade 테스트")
public class TrainSearchFacadeSeatStatusTest {

	@Autowired
	private TrainSearchFacade trainSearchFacade;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MemberRepository memberRepository;

	record SeatStatusScenario(
		String description,
		int standardCars, int firstClassCars, int standardRows, int firstClassRows,
		int reservedStandardSeats, int reservedFirstClassSeats,
		int passengerCount,
		SeatAvailabilityStatus expectedStandardStatus,
		SeatAvailabilityStatus expectedFirstClassStatus,
		boolean expectedStandardCanReserve,
		boolean expectedFirstClassCanReserve
	) {
		@Override
		public String toString() {
			return description;
		}
	}

	static Stream<SeatStatusScenario> seatStatusScenarios() {
		return Stream.of(
			new SeatStatusScenario(
				"1. 여유 상황 - 일반실/특실 모두 충분",
				2, 1, 10, 8,    // 일반실 80석, 특실 24석
				5, 2,      // 일반실 5석, 특실 2석 예약 → 75석, 22석 잔여
				4,              // 4명 요청
				// 일반실: 75/80 = 93.7% > 25% → AVAILABLE
				// 특실: 22/24 = 91.6% > 25% → AVAILABLE
				SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE,
				true, true
			),
			new SeatStatusScenario(
				"2. 제한적 상황 - 일반실 여유 부족하지만 예약 가능",
				2, 1, 10, 8,   // 일반실 80석, 특실 24석
				65, 5,   // 일반실 65석, 특실 5석 예약 → 15석, 19석 잔여
				4,             // 4명 요청
				// 일반실: 15/80 = 18.75% < 25% → LIMITED
				// 특실: 19/24 = 79.1% > 25% → AVAILABLE
				SeatAvailabilityStatus.LIMITED, SeatAvailabilityStatus.AVAILABLE,
				true, true
			),
			new SeatStatusScenario(
				"3. 완전 매진 상황",
				2, 1, 10, 8,   // 일반실 80석, 특실 24석 → 총 104석
				80, 20,    // 일반실 매진, 특실 20석 예약 → 4석 잔여
				6,             // 6명 요청 (특실 4석으로 수용 불가)
				// 일반실: 0석 잔여 → SOLD_OUT
				// 특실: 4 < 6명 요청 → INSUFFICIENT
				SeatAvailabilityStatus.SOLD_OUT, SeatAvailabilityStatus.INSUFFICIENT,
				false, false
			)
		);
	}

	@DisplayName("다양한 기존 예약 상황에 따라 적절한 좌석 상태를 표시한다.")
	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("seatStatusScenarios")
	void shouldDisplayCorrectSeatForAllScenarios(SeatStatusScenario scenario) {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		Member member = memberRepository.save(MemberFixture.createStandardMember());

		Train train = trainTestHelper.createRealisticTrain(
			scenario.standardCars, scenario.firstClassCars,
			scenario.standardRows, scenario.firstClassRows);

		TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop departureStop = trainScheduleTestHelper.getScheduleStopByStationName(schedule, "서울");
		ScheduleStop arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(schedule, "부산");

		if (scenario.reservedStandardSeats > 0) {
			List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, scenario.reservedStandardSeats);
			bookingTestHelper.createReservationWithSeatIds(member, schedule, departureStop, arrivalStop, seatIds,
				PassengerType.ADULT);
		}
		if (scenario.reservedFirstClassSeats > 0) {
			List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.FIRST_CLASS,
				scenario.reservedFirstClassSeats);
			bookingTestHelper.createReservationWithSeatIds(member, schedule, departureStop, arrivalStop, seatIds,
				PassengerType.ADULT);
		}

		// when
		TrainSearchRequest request = new TrainSearchRequest(seoul.getId(), busan.getId(), searchDate,
			scenario.passengerCount, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse trainResult = response.content().get(0);

		assertThat(trainResult.standardSeat().status()).isEqualTo(scenario.expectedStandardStatus);
		assertThat(trainResult.standardSeat().canReserve()).isEqualTo(scenario.expectedStandardCanReserve);
		assertThat(trainResult.firstClassSeat().status()).isEqualTo(scenario.expectedFirstClassStatus);
		assertThat(trainResult.firstClassSeat().canReserve()).isEqualTo(scenario.expectedFirstClassCanReserve);

		int expectedStandardTotal = scenario.standardCars * scenario.standardRows * 4;
		int expectedFirstClassTotal = scenario.firstClassCars * scenario.firstClassRows * 3;
		int expectedStandardRemaining = expectedStandardTotal - scenario.reservedStandardSeats;
		int expectedFirstClassRemaining = expectedFirstClassTotal - scenario.reservedFirstClassSeats;

		assertThat(trainResult.standardSeat().totalSeats()).isEqualTo(expectedStandardTotal);
		assertThat(trainResult.standardSeat().remainingSeats()).isEqualTo(expectedStandardRemaining);
		assertThat(trainResult.firstClassSeat().totalSeats()).isEqualTo(expectedFirstClassTotal);
		assertThat(trainResult.firstClassSeat().remainingSeats()).isEqualTo(expectedFirstClassRemaining);
	}

	/**
	 * 열차 스케줄 생성 헬퍼
	 */
	private TrainScheduleWithStopStations createTrainSchedule(Train train,
		LocalDate operationDate,
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

	private TrainSearchResponse findTrainByTime(List<TrainSearchResponse> trains, LocalTime time) {
		return trains.stream()
			.filter(train -> train.departureTime().equals(time))
			.findFirst()
			.orElseThrow(() -> new AssertionError("시간 " + time + "에 해당하는 열차를 찾을 수 없습니다"));
	}
}
