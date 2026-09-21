package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
import com.sudo.raillo.support.helper.TrainCacheTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 여러 스레드가 동시에 같은 좌석을 예약할 때 Lua 점유 검사가 하나만 통과시키는지 검증한다.
 */
@ServiceTest
@DisplayName("ReservationFacade - 동시 예약")
class ReservationConcurrencyTest {

	private static final int THREAD_COUNT = 10;

	@Autowired
	private ReservationFacade reservationFacade;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCacheTestHelper trainCacheTestHelper;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancyTestHelper;

	private String memberNo;
	private Train train;
	private TrainScheduleResult scheduleResult;
	private Long scheduleId;
	private Long seoulId;
	private Long daejeonId;
	private Long daeguId;
	private Long busanId;
	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		Member member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();
		train = trainTestHelper.createMediumTestTrain();
		// 서울(0) → 대전(1) → 동대구(2) → 부산(3)
		scheduleResult = trainScheduleTestHelper.builder()
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(6, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("동대구", LocalTime.of(8, 0), LocalTime.of(8, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 59000, 83000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "동대구", 35000, 49000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 40000, 56000);
		trainCacheTestHelper.seed(train, scheduleResult);

		scheduleId = scheduleResult.trainSchedule().getId();
		seoulId = scheduleResult.scheduleStops().get(0).getStation().getId();
		daejeonId = scheduleResult.scheduleStops().get(1).getStation().getId();
		daeguId = scheduleResult.scheduleStops().get(2).getStation().getId();
		busanId = scheduleResult.scheduleStops().get(3).getStation().getId();
		executorService = Executors.newFixedThreadPool(THREAD_COUNT);
	}

	@AfterEach
	void tearDown() {
		executorService.shutdownNow();
	}

	private record Outcome(int success, List<String> failureCodes) {
	}

	private Outcome runConcurrently(IntFunction<ReservationCreateRequest> requestForThread) throws InterruptedException {
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
		AtomicInteger successCount = new AtomicInteger();
		List<String> failureCodes = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < THREAD_COUNT; i++) {
			ReservationCreateRequest request = requestForThread.apply(i);
			executorService.submit(() -> {
				try {
					startLatch.await();
					reservationFacade.createReservation(request, memberNo);
					successCount.incrementAndGet();
				} catch (BusinessException e) {
					failureCodes.add(e.getErrorCode().getCode());
				} catch (Exception e) {
					failureCodes.add(e.getClass().getSimpleName());
				} finally {
					doneLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		doneLatch.await();
		return new Outcome(successCount.get(), failureCodes);
	}

	@Test
	@DisplayName("같은 좌석, 같은 구간에 10개 스레드가 동시 예약하면 1개만 성공하고 나머지는 Hold 충돌이다")
	void same_seat_same_section() throws InterruptedException {
		// given
		Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

		// when
		Outcome outcome = runConcurrently(i -> new ReservationCreateRequest(
			scheduleId, seoulId, busanId, List.of(PassengerType.ADULT), List.of(seat.getId())));

		// then
		assertThat(outcome.success()).isEqualTo(1);
		assertThat(outcome.failureCodes()).hasSize(THREAD_COUNT - 1)
			.allMatch(BookingError.SEAT_CONFLICT_WITH_RESERVATION.getCode()::equals);
		assertThat(seatOccupancyTestHelper.entries(scheduleId, seat.getTrainCar().getId()))
			.hasSize(3)
			.allSatisfy((field, value) -> assertThat(value.toString()).startsWith("R:"));
		assertThat(seatOccupancyTestHelper.entries(scheduleId, seat.getTrainCar().getId()).values().stream().distinct())
			.hasSize(1);
	}

	@Test
	@DisplayName("같은 좌석, 겹치는 구간에 동시 예약하면 1개만 성공한다")
	void same_seat_overlapping_sections() throws InterruptedException {
		// given - 짝수 스레드는 서울→동대구(0,1), 홀수 스레드는 대전→부산(1,2). 구간 1이 겹친다
		Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

		// when
		Outcome outcome = runConcurrently(i -> i % 2 == 0
			? new ReservationCreateRequest(scheduleId, seoulId, daeguId, List.of(PassengerType.ADULT), List.of(seat.getId()))
			: new ReservationCreateRequest(scheduleId, daejeonId, busanId, List.of(PassengerType.ADULT), List.of(seat.getId())));

		// then
		assertThat(outcome.success()).isEqualTo(1);
		assertThat(outcome.failureCodes()).hasSize(THREAD_COUNT - 1)
			.allMatch(BookingError.SEAT_CONFLICT_WITH_RESERVATION.getCode()::equals);
	}

	@Test
	@DisplayName("서로 다른 좌석에 동시 예약하면 모두 성공한다")
	void different_seats() throws InterruptedException {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, THREAD_COUNT);
		assertThat(seats).hasSize(THREAD_COUNT);

		// when
		Outcome outcome = runConcurrently(i -> new ReservationCreateRequest(
			scheduleId, seoulId, busanId, List.of(PassengerType.ADULT), List.of(seats.get(i).getId())));

		// then
		assertThat(outcome.failureCodes()).isEmpty();
		assertThat(outcome.success()).isEqualTo(THREAD_COUNT);
	}
}
