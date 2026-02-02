package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.AssertionsForClassTypes.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.RedisTest;

@RedisTest
@DisplayName("SeatHoldRepository 테스트")
class SeatHoldRepositoryTest {

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	private static final Long TRAIN_SCHEDULE_ID = 1001L;
	private static final Long SEAT_ID = 12L;
	private static final long TEST_SOLD_TTL_SECONDS = 3600L; // 테스트용 1시간

	@Nested
	@DisplayName("단일 좌석 Hold")
	class SingleSeatHoldTest {

		@Test
		@DisplayName("충돌이 없는 구간은 Hold에 성공한다")
		void hold_success_when_no_conflict() {
			// given
			String pendingBookingId = "pending_001";
			int departureStopOrder = 0;
			int arrivalStopOrder = 3;  // 구간: 0-1, 1-2, 2-3

			// when
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId,
				departureStopOrder, arrivalStopOrder
			);

			// then
			assertThat(result.success()).isTrue();
			assertThat(result.status()).isEqualTo("HOLD_SUCCESS");
		}

		@Test
		@DisplayName("겹치지 않는 구간은 서로 Hold가 가능하다")
		void hold_success_when_non_overlapping_sections() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			// 첫 번째 Hold: 0-1, 1-2 구간
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 2);

			// when - 두 번째 Hold: 2-3, 3-4 구간 (겹치지 않음)
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 4
			);

			// then
			assertThat(result.success()).isTrue();
		}

		@Test
		@DisplayName("기존 Hold와 겹치는 구간은 충돌이 발생한다")
		void hold_fail_when_conflict_with_existing_hold() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			// 첫 번째 Hold: 0-1, 1-2, 2-3 구간
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 3);

			// when - 두 번째 Hold: 2-3, 3-4 구간 (2-3 겹침)
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 4
			);

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithHold()).isTrue();
			assertThat(result.conflictSection()).isEqualTo("2-3");
		}

		@Test
		@DisplayName("다양한 구간 길이에서 겹치는 구간은 Hold에 실패한다")
		void various_section_lengths_conflict_test() {
			// 1. 장거리 먼저 Hold (0-5)
			SeatHoldResult result1 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_0", 0, 5
			);
			assertThat(result1.success()).isTrue();

			// 2. 단거리 시도 - 겹침 (0-2)
			SeatHoldResult result2 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_1", 0, 2
			);
			assertThat(result2.success()).isFalse();
			assertThat(result2.isConflictWithHold()).isTrue();

			// 3. 단거리 시도 - 겹침 (3-4)
			SeatHoldResult result3 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_2", 3, 4
			);
			assertThat(result3.success()).isFalse();

			// 4. 독립 구간 - 성공 (5-10)
			SeatHoldResult result4 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_3", 5, 10
			);
			assertThat(result4.success()).isTrue();

			// 5. 단거리 시도 - 겹침 (7-8)
			SeatHoldResult result5 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_4", 7, 8
			);
			assertThat(result5.success()).isFalse();
		}
	}

	@Nested
	@DisplayName("Sold 충돌")
	class SoldConflictTest {

		@Test
		@DisplayName("Sold 구간과 겹치면 충돌이 발생한다")
		void hold_fail_when_conflict_with_sold() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			// 첫 번째 Hold 후 Confirm (Sold로 전환)
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 2);
			seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, TEST_SOLD_TTL_SECONDS);

			// when - Sold 구간과 겹치는 Hold 시도
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 1, 3
			);

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithSold()).isTrue();
			assertThat(result.conflictSection()).isEqualTo("1-2");
		}
	}

	@Nested
	@DisplayName("Confirm & Release")
	class ConfirmAndReleaseTest {

		@Test
		@DisplayName("Hold 확정에 성공한다")
		void confirm_hold_success() {
			// given
			String pendingBookingId = "pending_001";
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, 0, 3);

			// when & then
			assertThatCode(() ->
				seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, TEST_SOLD_TTL_SECONDS)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("존재하지 않는 Hold 확정 시 예외가 발생한다")
		void confirm_fail_when_hold_not_exists() {
			// when & then
			assertThatThrownBy(() -> seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, "non_existent", TEST_SOLD_TTL_SECONDS))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_HOLD_NOT_FOUND)
				.hasMessage(BookingError.SEAT_HOLD_NOT_FOUND.getMessage());
		}

		@Test
		@DisplayName("Hold 해제 후 같은 구간을 다시 Hold 할 수 있다")
		void release_then_hold_again_success() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 3);

			// when
			seatHoldRepository.releaseHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1);

			// then
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 0, 3
			);
			assertThat(result.success()).isTrue();
		}
	}

	@Nested
	@DisplayName("동시성 테스트")
	class ConcurrencyTest {

		@Test
		@DisplayName("100명이 동시에 같은 좌석 같은 구간 Hold 시도 시 1명만 성공한다")
		void concurrent_hold_same_seat_same_section() throws InterruptedException {
			// given
			int numberOfThreads = 100;
			ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

			CountDownLatch readyLatch = new CountDownLatch(numberOfThreads); // 준비 완료
			CountDownLatch startLatch = new CountDownLatch(1);              // 동시 시작
			CountDownLatch doneLatch = new CountDownLatch(numberOfThreads); // 종료 대기

			AtomicInteger successCount = new AtomicInteger(0);
			AtomicInteger failCount = new AtomicInteger(0);

			// when
			for (int i = 0; i < numberOfThreads; i++) {
				final int index = i;
				executorService.submit(() -> {
					try {
						readyLatch.countDown();		// 스레드 준비 완료 알림
						startLatch.await();			// 모든 스레드가 여기서 대기, 동시에 출발

						SeatHoldResult result = seatHoldRepository.tryHold(
							TRAIN_SCHEDULE_ID,
							SEAT_ID,
							"pending_" + index,
							0,
							3
						);

						if (result.success()) {
							successCount.incrementAndGet();
						} else {
							failCount.incrementAndGet();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						doneLatch.countDown();
					}
				});
			}

			readyLatch.await(); 		// 모든 스레드가 준비될 때까지 대기
			startLatch.countDown(); 	// 동시에 시작
			doneLatch.await(); 			// 모든 작업 완료 대기
			executorService.shutdown();

			// then
			assertThat(successCount.get()).isEqualTo(1);
			assertThat(failCount.get()).isEqualTo(numberOfThreads - 1);
		}

		@Test
		@DisplayName("10명이 동시에 같은 좌석 다른 구간 Hold 시도 시 겹치지 않으면 모두 성공한다")
		void concurrent_hold_same_seat_different_sections() throws InterruptedException {
			// given
			int numberOfThreads = 10;
			ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
			CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
			AtomicInteger successCount = new AtomicInteger(0);

			// when
			for (int i = 0; i < numberOfThreads; i++) {
				final int index = i;
				executorService.submit(() -> {
					try {
						readyLatch.countDown();
						startLatch.await();

						SeatHoldResult result = seatHoldRepository.tryHold(
							TRAIN_SCHEDULE_ID, SEAT_ID, "pending_" + index,
							index, index + 1
						);
						if (result.success()) {
							successCount.incrementAndGet();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						doneLatch.countDown();
					}
				});
			}

			readyLatch.await();
			startLatch.countDown();
			doneLatch.await();
			executorService.shutdown();

			// then
			assertThat(successCount.get()).isEqualTo(numberOfThreads);
		}
	}
}
