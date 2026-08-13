package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.service.SeatOccupancyService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
@DisplayName("SeatOccupancyQueryRepository 점유 좌석 집계")
class SeatOccupancyQueryRepositoryTest {

	private static final String MEMBER_NO = "202601010001";

	@Autowired
	private SeatOccupancyQueryRepository seatOccupancyQueryRepository;
	@Autowired
	private SeatOccupancyService seatOccupancyService;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private TrainTestHelper trainTestHelper;
	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Train train;
	private TrainScheduleResult scheduleResult;
	private Long trainScheduleId;
	private ScheduleStop seoul;
	private ScheduleStop daejeon;
	private ScheduleStop dongdaegu;
	private ScheduleStop busan;

	@BeforeEach
	void setUp() {
		// 서울(0) -> 대전(1) -> 동대구(2) -> 부산(3)
		train = trainTestHelper.createSmallTestTrain();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);

		scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(6, 0), LocalTime.of(6, 5))
			.addStop("동대구", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(8, 0), null)
			.build();

		trainScheduleId = scheduleResult.trainSchedule().getId();
		seoul = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "서울");
		daejeon = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "대전");
		dongdaegu = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "동대구");
		busan = trainScheduleTestHelper.getScheduleStopByStationName(scheduleResult, "부산");
	}

	@Test
	@DisplayName("여러 구간에 걸쳐 점유된 좌석도 CarType별 집계에서 한 번만 계수된다")
	void countOccupiedSeatsBatch_counts_multi_section_seat_once() {
		// given - 서울 -> 부산 (3개 구간)에 일반실 2석 점유 = 점유 행 6개
		holdSeats(seoul, busan, trainTestHelper.getSeats(train, CarType.STANDARD, 2));

		// when
		Map<Long, Map<CarType, Integer>> occupied = seatOccupancyQueryRepository.countOccupiedSeatsBatch(
			List.of(trainScheduleId), stationId(seoul), stationId(busan), LocalDateTime.now());

		// then - 행은 6개지만 좌석은 2개
		assertThat(occupied.get(trainScheduleId)).containsEntry(CarType.STANDARD, 2);
	}

	@Test
	@DisplayName("CarType별로 점유 좌석 수가 분리 집계된다")
	void countOccupiedSeatsBatch_groups_by_car_type() {
		// given
		holdSeats(seoul, busan, trainTestHelper.getSeats(train, CarType.STANDARD, 2));
		holdSeats(seoul, busan, trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 3));

		// when
		Map<Long, Map<CarType, Integer>> occupied = seatOccupancyQueryRepository.countOccupiedSeatsBatch(
			List.of(trainScheduleId), stationId(seoul), stationId(busan), LocalDateTime.now());

		// then
		assertThat(occupied.get(trainScheduleId))
			.containsEntry(CarType.STANDARD, 2)
			.containsEntry(CarType.FIRST_CLASS, 3);
	}

	@Test
	@DisplayName("요청 구간과 겹치지 않는 점유는 집계되지 않는다")
	void countOccupiedSeatsBatch_excludes_non_overlapping_section() {
		// given - 동대구(2) -> 부산(3) 점유
		holdSeats(dongdaegu, busan, trainTestHelper.getSeats(train, CarType.STANDARD, 2));

		// when - 서울(0) -> 대전(1) 구간 조회
		Map<Long, Map<CarType, Integer>> occupied = seatOccupancyQueryRepository.countOccupiedSeatsBatch(
			List.of(trainScheduleId), stationId(seoul), stationId(daejeon), LocalDateTime.now());

		// then
		assertThat(occupied).isEmpty();
	}

	@Test
	@DisplayName("만료된 점유는 집계에서 제외된다")
	void countOccupiedSeatsBatch_excludes_expired_occupancy() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		Reservation expired = createReservation(seoul, busan, LocalDateTime.now().minusMinutes(1));
		seatOccupancyService.hold(expired, seats);

		// when
		Map<Long, Map<CarType, Integer>> occupied = seatOccupancyQueryRepository.countOccupiedSeatsBatch(
			List.of(trainScheduleId), stationId(seoul), stationId(busan), LocalDateTime.now());

		// then
		assertThat(occupied).isEmpty();
	}

	@Test
	@DisplayName("객차별 점유 좌석 수를 집계한다")
	void countOccupiedSeatsByTrainCar_groups_by_train_car() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);
		holdSeats(seoul, busan, seats);
		Long trainCarId = seats.get(0).getTrainCar().getId();

		// when
		Map<Long, Integer> occupied = seatOccupancyQueryRepository.countOccupiedSeatsByTrainCar(
			trainScheduleId, seoul.getStopOrder(), busan.getStopOrder(), LocalDateTime.now());

		// then
		assertThat(occupied).containsEntry(trainCarId, 3);
	}

	@Test
	@DisplayName("특정 객차에서 요청 구간에 점유된 좌석 ID 목록을 조회한다")
	void findOccupiedSeatIds_returns_occupied_seats_in_car() {
		// given - 서울(0) -> 대전(1) 에만 점유
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		holdSeats(seoul, daejeon, seats);
		Long trainCarId = seats.get(0).getTrainCar().getId();

		// when
		Set<Long> occupiedInFirstSection = seatOccupancyQueryRepository.findOccupiedSeatIds(
			trainScheduleId, trainCarId, seoul.getStopOrder(), daejeon.getStopOrder(), LocalDateTime.now());
		Set<Long> occupiedInLastSection = seatOccupancyQueryRepository.findOccupiedSeatIds(
			trainScheduleId, trainCarId, dongdaegu.getStopOrder(), busan.getStopOrder(), LocalDateTime.now());

		// then
		assertThat(occupiedInFirstSection)
			.containsExactlyInAnyOrderElementsOf(seats.stream().map(Seat::getId).toList());
		assertThat(occupiedInLastSection).isEmpty();
	}

	@Test
	@DisplayName("요청 좌석 중 이미 점유된 좌석만 골라낸다")
	void findOccupiedSeatIdsAmong_returns_only_conflicting_seats() {
		// given - 3석 중 1석만 점유
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);
		Seat occupiedSeat = seats.get(1);
		holdSeats(seoul, busan, List.of(occupiedSeat));

		// when
		Set<Long> conflicting = seatOccupancyQueryRepository.findOccupiedSeatIdsAmong(
			trainScheduleId,
			seats.stream().map(Seat::getId).toList(),
			daejeon.getStopOrder(),
			busan.getStopOrder(),
			LocalDateTime.now()
		);

		// then
		assertThat(conflicting).containsExactly(occupiedSeat.getId());
	}

	// ===== Helper =====

	private void holdSeats(ScheduleStop departureStop, ScheduleStop arrivalStop, List<Seat> seats) {
		seatOccupancyService.hold(createReservation(departureStop, arrivalStop, LocalDateTime.now().plusMinutes(10)), seats);
	}

	private Reservation createReservation(ScheduleStop departureStop, ScheduleStop arrivalStop, LocalDateTime expiresAt) {
		return reservationRepository.save(Reservation.create(
			"PB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
			MEMBER_NO,
			scheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			BigDecimal.valueOf(50000),
			expiresAt
		));
	}

	private Long stationId(ScheduleStop scheduleStop) {
		Station station = scheduleStop.getStation();
		return station.getId();
	}
}
