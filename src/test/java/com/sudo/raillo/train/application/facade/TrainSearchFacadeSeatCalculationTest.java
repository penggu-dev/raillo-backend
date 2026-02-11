package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.request.TrainSearchRequest;
import com.sudo.raillo.train.application.dto.response.TrainSearchResponse;
import com.sudo.raillo.train.application.dto.response.TrainSearchSlicePageResponse;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

@ServiceTest
@DisplayName("열차 검색 좌석 계산 통합 테스트")
class TrainSearchFacadeSeatCalculationTest {

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

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	@Test
	@DisplayName("SeatBooking과 Hold가 모두 잔여석에 반영된다")
	void searchTrains_reflects_both_seatBooking_and_hold() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		Member member = memberRepository.save(MemberFixture.create());

		// 일반실 80석 (2객차 × 10행 × 4석), 특실 24석 (1객차 × 8행 × 3석)
		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 8);

		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop departureStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// SeatBooking: 일반실 10석 예약
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 10);
		bookingTestHelper.builder(member, scheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeats(standardSeats, PassengerType.ADULT)
			.build();

		// Hold: 일반실 5석, 특실 3석 임시 점유
		List<Seat> holdStandardSeats = trainTestHelper.getAvailableSeats(
			scheduleResult.trainSchedule(), CarType.STANDARD, 5);
		List<Seat> holdFirstClassSeats = trainTestHelper.getAvailableSeats(
			scheduleResult.trainSchedule(), CarType.FIRST_CLASS, 3);

		holdSeats(holdStandardSeats, trainScheduleId, departureStop, arrivalStop);
		holdSeats(holdFirstClassSeats, trainScheduleId, departureStop, arrivalStop);

		// when
		TrainSearchRequest request = new TrainSearchRequest(seoul.getId(), busan.getId(), searchDate, 1, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 일반실: 80 - 10(SeatBooking) - 5(Hold) = 65
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(65);
		assertThat(result.standardSeat().totalSeats()).isEqualTo(80);

		// 특실: 24 - 0(SeatBooking) - 3(Hold) = 21
		assertThat(result.firstClassSeat().remainingSeats()).isEqualTo(21);
		assertThat(result.firstClassSeat().totalSeats()).isEqualTo(24);
	}

	@Test
	@DisplayName("Hold만 있는 경우에도 잔여석에 반영된다")
	void searchTrains_reflects_hold_only() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 8);

		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop departureStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// Hold: 일반실 8석 임시 점유
		List<Seat> holdStandardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 8);
		holdSeats(holdStandardSeats, trainScheduleId, departureStop, arrivalStop);

		// when
		TrainSearchRequest request = new TrainSearchRequest(
			seoul.getId(), busan.getId(), searchDate, 1, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 일반실: 80 - 8(Hold) = 72
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(72);
		assertThat(result.standardSeat().totalSeats()).isEqualTo(80);

		// 특실: Hold 없으므로 전체 좌석 유지
		assertThat(result.firstClassSeat().remainingSeats()).isEqualTo(24);
	}

	@Test
	@DisplayName("SeatBooking과 Hold 합산으로 인원 수용이 불가능하면 예약 불가로 판단한다")
	void searchTrains_not_reservable_when_combined_insufficient() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 80000);
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		Member member = memberRepository.save(MemberFixture.create());

		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 8);

		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop departureStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// SeatBooking: 일반실 76석 예약
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 76);
		bookingTestHelper.builder(member, scheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeats(standardSeats, PassengerType.ADULT)
			.build();

		// Hold: 일반실 잔여 3석 중 2석 Hold → 잔여 2석
		List<Seat> holdStandardSeats = trainTestHelper.getAvailableSeats(
			scheduleResult.trainSchedule(), CarType.STANDARD, 2);
		holdSeats(holdStandardSeats, trainScheduleId, departureStop, arrivalStop);

		// when - 4명 예약 요청
		TrainSearchRequest request = new TrainSearchRequest(
			seoul.getId(), busan.getId(), searchDate, 4, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 일반실: 80 - 76 - 2 = 2석 잔여, 4명 요청이므로 예약 불가
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(2);
		assertThat(result.standardSeat().canReserve()).isFalse();
	}

	private void holdSeats(
		List<Seat> seats,
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop
	) {
		String pendingBookingId = "pending_test_" + System.nanoTime();
		int departureStopOrder = departureStop.getStopOrder();
		int arrivalStopOrder = arrivalStop.getStopOrder();

		seats.forEach(seat -> seatHoldRepository.tryHold(
			trainScheduleId,
			seat.getId(),
			pendingBookingId,
			departureStopOrder,
			arrivalStopOrder,
			seat.getTrainCar().getId()
		));
	}
}
