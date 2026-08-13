package com.sudo.raillo.train.application.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;

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
	private SeatOccupancyTestHelper seatOccupancyTestHelper;

	@Autowired
	private RedisTemplate<String, String> customStringRedisTemplate;

	@Test
	@DisplayName("SeatBooking과 Seat Hold가 모두 잔여석에 반영된다")
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

		// Seat Hold: 일반실 5석, 특실 3석
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
	@DisplayName("Seat Hold만 있는 경우에도 잔여석에 반영된다")
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

		// Seat Hold: 일반실 8석
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
	@DisplayName("SeatBooking과 Seat Hold 합산으로 인원 수용이 불가능하면 예약 불가로 판단한다")
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

	@Test
	@DisplayName("Seat Hold 구간이 검색 구간과 겹치지 않으면 잔여석에 반영되지 않는다")
	void searchTrains_hold_non_overlapping_section_not_counted() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 25000, 40000);
		Station daejeon = trainScheduleTestHelper.getOrCreateStation("대전");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		// 일반실 80석 (2객차 × 10행 × 4석), 특실 24석 (1객차 × 8행 × 3석)
		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 8);

		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("대전", LocalTime.of(11, 0), LocalTime.of(11, 5))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop seoulStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop daejeonStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "대전");
		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// 일반실 5석을 서울 -> 대전 구간(section "0-1")으로 점유
		List<Seat> holdStandardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 5);
		holdSeats(holdStandardSeats, trainScheduleId, seoulStop, daejeonStop);

		// when
		// 검색 구간: 대전 -> 부산(section "1-2")
		TrainSearchRequest request = new TrainSearchRequest(daejeon.getId(), busan.getId(), searchDate, 1, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 일반실: Hold 구간(서울 -> 대전)이 검색 구간(대전 -> 부산)과 겹치지 않으므로 잔여석 감소 없음
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(80);
	}

	@Test
	@DisplayName("Seat Hold 구간이 검색 구간과 겹치면 잔여석에 반영된다")
	void searchTrains_hold_overlapping_section_is_counted() {
		// given
		LocalDate searchDate = LocalDate.now().plusDays(1);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 25000, 40000);
		Station daejeon = trainScheduleTestHelper.getOrCreateStation("대전");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");

		Train train = trainTestHelper.createRealisticTrain(2, 1, 10, 8);

		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX TEST")
			.operationDate(searchDate)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("대전", LocalTime.of(11, 0), LocalTime.of(11, 5))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		ScheduleStop seoulStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop busanStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// 일반실 5석을 서울 -> 부산 구간(sections "0-1", "1-2")으로 점유
		List<Seat> holdStandardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 5);
		holdSeats(holdStandardSeats, trainScheduleId, seoulStop, busanStop);

		// when
		// 검색 구간: 대전 -> 부산(section "1-2")
		TrainSearchRequest request = new TrainSearchRequest(daejeon.getId(), busan.getId(), searchDate, 1, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 일반실: Hold 구간(서울 -> 부산)이 검색 구간(대전 -> 부산)의 section "1-2"와 겹치므로 5석 차감
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(75);
	}

	@Test
	@DisplayName("만료된 좌석 점유는 잔여석에 반영되지 않는다")
	void searchTrains_expired_hold_not_counted() {
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

		Long trainScheduleId = scheduleResult.trainSchedule().getId();

		// 5석 중 1석은 만료된 점유, 4석은 유효한 점유
		ScheduleStop seoulStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		ScheduleStop busanStop = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 5);

		seatOccupancyTestHelper.hold(scheduleResult.trainSchedule(), seoulStop, busanStop,
			List.of(seats.get(0)), LocalDateTime.now().minusMinutes(1));
		seatOccupancyTestHelper.hold(scheduleResult.trainSchedule(), seoulStop, busanStop,
			seats.subList(1, seats.size()));

		// when
		TrainSearchRequest request = new TrainSearchRequest(seoul.getId(), busan.getId(), searchDate, 1, "00");
		TrainSearchSlicePageResponse response = trainSearchFacade.searchTrains(request, PageRequest.of(0, 20));

		// then
		assertThat(response.content()).hasSize(1);
		TrainSearchResponse result = response.content().get(0);

		// 만료된 1석은 집계에서 제외, 유효한 4석만 차감: 80 - 4 = 76
		assertThat(result.standardSeat().remainingSeats()).isEqualTo(76);
	}

	private void holdSeats(
		List<Seat> seats,
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop
	) {
		seatOccupancyTestHelper.hold(
			departureStop.getTrainSchedule(), departureStop, arrivalStop, seats);
	}
}
