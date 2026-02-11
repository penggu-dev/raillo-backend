package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.facade.PendingBookingFacade;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PendingBooking 생성 동시성 테스트
 * <p>여러 스레드가 동시에 같은 좌석을 예약할 때 올바르게 동작하는지 검증</p>
 */
@ServiceTest
public class PendingBookingConcurrencyTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private List<ScheduleStop> stops;
	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createKTX();

		// 서울(0) → 대전(1) → 동대구(2) → 부산(3)
		trainScheduleResult = trainScheduleTestHelper.builder()
			.train(train)
			.addStop("서울", null, LocalTime.of(6, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("동대구", LocalTime.of(8, 0), LocalTime.of(8, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();

		stops = trainScheduleResult.scheduleStops();

		// 역간 요금 정보 생성
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 23000, 32000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "동대구", 35000, 49000);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 59000, 83000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "동대구", 15000, 21000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 40000, 56000);
		trainScheduleTestHelper.createOrUpdateStationFare("동대구", "부산", 20000, 28000);

		// 스레드 풀 생성 (테스트별로 재사용)
		executorService = Executors.newFixedThreadPool(20);
	}

	@AfterEach
	void tearDown() {
		if (executorService != null && !executorService.isShutdown()) {
			executorService.shutdownNow();
		}
	}

	@Test
	@DisplayName("같은 좌석, 같은 구간에 10개 스레드가 동시 예약 시도하면 1개만 성공한다")
	void sameSeat_sameSection_concurrent_onlyOneSucceeds() throws InterruptedException {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		Seat seat = seats.get(0);

		int threadCount = 10;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);
		List<String> successfulBookingIds = new ArrayList<>();

		// when: 10개 스레드가 동시에 같은 좌석 예약 시도
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					// 모든 스레드가 준비될 때까지 대기
					startLatch.await();

					// 예약 시도
					PendingBookingCreateRequest request = new PendingBookingCreateRequest(
						trainScheduleResult.trainSchedule().getId(),
						stops.get(0).getStation().getId(),  // 서울
						stops.get(3).getStation().getId(),  // 부산
						List.of(PassengerType.ADULT),
						List.of(seat.getId())
					);

					PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
						request,
						member.getMemberDetail().getMemberNo()
					);

					synchronized (successfulBookingIds) {
						successfulBookingIds.add(response.pendingBookingId());
					}
					successCount.incrementAndGet();

				} catch (Exception e) {
					failCount.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		// 모든 스레드 동시 시작
		startLatch.countDown();

		// 모든 스레드 완료 대기 (최대 10초)
		doneLatch.await();

		// then: 정확히 1개만 성공해야 함
		assertThat(successCount.get()).isEqualTo(1);
		assertThat(failCount.get()).isEqualTo(9);
		assertThat(successfulBookingIds).hasSize(1);
	}

	@Test
	@DisplayName("같은 좌석, 겹치는 구간에 10개 스레드가 동시 예약 시도하면 1개만 성공한다")
	void sameSeat_overlappingSections_concurrent_onlyOneSucceeds() throws InterruptedException {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		Seat seat = seats.get(0);

		int threadCount = 10;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			final int threadIndex = i;
			final boolean isShortRoute = i < 5;

			executorService.submit(() -> {
				try {
					startLatch.await();

					PendingBookingCreateRequest request = new PendingBookingCreateRequest(
						trainScheduleResult.trainSchedule().getId(),
						stops.get(0).getStation().getId(),  // 서울
						isShortRoute
							? stops.get(1).getStation().getId()  // 대전 (section 0-1)
							: stops.get(2).getStation().getId(), // 동대구 (section 0-1, 1-2)
						List.of(PassengerType.ADULT),
						List.of(seat.getId())
					);

					PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
						request,
						member.getMemberDetail().getMemberNo()
					);

					successCount.incrementAndGet();
				} catch (Exception e) {
					failCount.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();

		// then: section 0-1이 겹치므로 1개만 성공
		assertThat(successCount.get()).isEqualTo(1);
		assertThat(failCount.get()).isEqualTo(9);
	}

	@Test
	@DisplayName("확정 예매 후 10개 스레드가 동시 예약 시도하면 모두 실패한다")
	void afterConfirmedBooking_concurrent_allFail() throws InterruptedException {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		Seat seat = seats.get(0);

		// 기존 확정 예매 생성 (서울 → 부산)
		BookingResult confirmedBooking = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seat, PassengerType.ADULT)
			.build();

		int threadCount = 10;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);
		List<String> failureReasons = new ArrayList<>();

		// when: 10개 스레드가 동시에 같은 좌석 예약 시도
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					startLatch.await();

					PendingBookingCreateRequest request = new PendingBookingCreateRequest(
						trainScheduleResult.trainSchedule().getId(),
						stops.get(0).getStation().getId(),  // 서울
						stops.get(3).getStation().getId(),  // 부산
						List.of(PassengerType.ADULT),
						List.of(seat.getId())
					);

					pendingBookingFacade.createPendingBooking(request, member.getMemberDetail().getMemberNo());
					successCount.incrementAndGet();

				} catch (BusinessException e) {
					failCount.incrementAndGet();
					synchronized (failureReasons) {
						failureReasons.add(e.getErrorCode().getCode());
					}
				} catch (Exception e) {
					failCount.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		doneLatch.await();

		// then
		assertThat(successCount.get()).isEqualTo(0);
		assertThat(failCount.get()).isEqualTo(10);
		assertThat(failureReasons)
			.hasSize(10)
			.allMatch(code -> code.equals(BookingError.SEAT_ALREADY_BOOKED.getCode()));
	}

	@Test
	@DisplayName("대규모 동시 요청 (100개 스레드)에서도 정확히 1개만 성공한다")
	void largeConcurrent_100Threads_onlyOneSucceeds() throws InterruptedException {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		Seat seat = seats.get(0);

		int threadCount = 100;
		ExecutorService largeExecutor = Executors.newFixedThreadPool(50);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		try {
			// when: 100개 스레드가 동시에 같은 좌석 예약 시도
			for (int i = 0; i < threadCount; i++) {
				final int threadIndex = i;
				largeExecutor.submit(() -> {
					try {
						startLatch.await();

						PendingBookingCreateRequest request = new PendingBookingCreateRequest(
							trainScheduleResult.trainSchedule().getId(),
							stops.get(0).getStation().getId(),  // 서울
							stops.get(3).getStation().getId(),  // 부산
							List.of(PassengerType.ADULT),
							List.of(seat.getId())
						);

						pendingBookingFacade.createPendingBooking(request, member.getMemberDetail().getMemberNo());
						successCount.incrementAndGet();
					} catch (Exception e) {
						failCount.incrementAndGet();
					} finally {
						doneLatch.countDown();
					}
				});
			}

			startLatch.countDown();
			doneLatch.await();

			// then: 100개 중 정확히 1개만 성공
			assertThat(successCount.get()).isEqualTo(1);
			assertThat(failCount.get()).isEqualTo(99);
		} finally {
			largeExecutor.shutdownNow();
		}
	}
}
