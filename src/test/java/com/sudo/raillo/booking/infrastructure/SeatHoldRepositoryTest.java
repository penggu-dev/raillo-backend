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
@DisplayName("SeatHoldRepository - Lua 스크립트 동작 검증")
class SeatHoldRepositoryTest {

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	private static final Long TRAIN_SCHEDULE_ID = 1001L;
	private static final Long SEAT_ID = 12L;

	@Nested
	@DisplayName("단일 좌석 Hold 테스트")
	class SingleSeatHoldTest {

		@Test
		@DisplayName("충돌 없는 구간에 Hold 성공")
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
		@DisplayName("겹치지 않는 구간은 서로 Hold 가능")
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
		@DisplayName("기존 Hold와 겹치는 구간은 충돌 발생")
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
	}

	@Nested
	@DisplayName("Sold 충돌 테스트")
	class SoldConflictTest {

		@Test
		@DisplayName("Sold 구간과 겹치면 충돌 발생")
		void hold_fail_when_conflict_with_sold() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			// 첫 번째 Hold 후 Confirm (Sold로 전환)
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 2);
			seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1);

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
	@DisplayName("다중 좌석 Hold 테스트")
	class MultipleSeatHoldTest {

		@Test
		@DisplayName("여러 좌석 동시 Hold 성공")
		void hold_multiple_seats_success() {
			// given
			String pendingBookingId = "pending_001";
			List<Long> seatIds = List.of(1L, 2L, 3L);

			// when & then - 예외 발생하지 않으면 성공
			assertThatCode(() ->
				seatHoldRepository.tryHoldSeats(
					TRAIN_SCHEDULE_ID, seatIds, pendingBookingId, 0, 3
				)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("여러 좌석 중 하나라도 충돌 시 전체 롤백")
		void hold_multiple_seats_rollback_on_conflict() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			// 좌석 2번에 먼저 Hold
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, 2L, pendingBookingId1, 0, 3);

			// when - 좌석 1, 2, 3 동시 Hold 시도 (2번에서 충돌)
			assertThatThrownBy(() ->
				seatHoldRepository.tryHoldSeats(
					TRAIN_SCHEDULE_ID, List.of(1L, 2L, 3L), pendingBookingId2, 0, 3
				)
			)
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException be = (BusinessException) e;
					assertThat(be.getErrorCode()).isEqualTo(BookingError.SEAT_CONFLICT_WITH_HOLD);
				});

			// then - 좌석 1번도 롤백되어 Hold 가능해야 함
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, 1L, "pending_003", 0, 3
			);
			assertThat(result.success()).isTrue();
		}
	}

	@Nested
	@DisplayName("Confirm & Release 테스트")
	class ConfirmAndReleaseTest {

		@Test
		@DisplayName("Hold 확정 성공")
		void confirm_hold_success() {
			// given
			String pendingBookingId = "pending_001";
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, 0, 3);

			// when & then
			assertThatCode(() ->
				seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("존재하지 않는 Hold 확정 시 예외")
		void confirm_fail_when_hold_not_exists() {
			// when & then
			assertThatThrownBy(() ->
				seatHoldRepository.confirmHold(TRAIN_SCHEDULE_ID, SEAT_ID, "non_existent")
			)
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException be = (BusinessException) e;
					assertThat(be.getErrorCode()).isEqualTo(BookingError.SEAT_HOLD_NOT_FOUND);
				});
		}

		@Test
		@DisplayName("Hold 해제 후 같은 구간 다시 Hold 가능")
		void release_then_hold_again_success() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 3);

			// when - 해제
			seatHoldRepository.releaseHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1);

			// then - 같은 구간 다시 Hold 가능
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
		@DisplayName("100명이 동시에 같은 좌석 같은 구간 Hold 시도 - 1명만 성공")
		void concurrent_hold_same_seat_same_section() throws InterruptedException {
			// given
			int numberOfThreads = 100;
			ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
			CountDownLatch latch = new CountDownLatch(numberOfThreads);
			AtomicInteger successCount = new AtomicInteger(0);
			AtomicInteger failCount = new AtomicInteger(0);

			// when
			for (int i = 0; i < numberOfThreads; i++) {
				final int index = i;
				executorService.submit(() -> {
					try {
						SeatHoldResult result = seatHoldRepository.tryHold(
							TRAIN_SCHEDULE_ID, SEAT_ID, "pending_" + index, 0, 3
						);
						if (result.success()) {
							successCount.incrementAndGet();
						} else {
							failCount.incrementAndGet();
						}
					} finally {
						latch.countDown();
					}
				});
			}

			latch.await();
			executorService.shutdown();

			// then
			assertThat(successCount.get()).isEqualTo(1);
			assertThat(failCount.get()).isEqualTo(99);
		}

		@Test
		@DisplayName("100명이 동시에 같은 좌석 다른 구간 Hold 시도 - 겹치지 않으면 모두 성공")
		void concurrent_hold_same_seat_different_sections() throws InterruptedException {
			// given - 구간이 안 겹치도록 설정
			// 사용자 0: 0-1, 사용자 1: 1-2, 사용자 2: 2-3, ...
			int numberOfThreads = 10;
			ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
			CountDownLatch latch = new CountDownLatch(numberOfThreads);
			AtomicInteger successCount = new AtomicInteger(0);

			// when
			for (int i = 0; i < numberOfThreads; i++) {
				final int index = i;
				executorService.submit(() -> {
					try {
						SeatHoldResult result = seatHoldRepository.tryHold(
							TRAIN_SCHEDULE_ID, SEAT_ID, "pending_" + index,
							index, index + 1  // 각자 다른 단일 구간
						);
						if (result.success()) {
							successCount.incrementAndGet();
						}
					} finally {
						latch.countDown();
					}
				});
			}

			latch.await();
			executorService.shutdown();

			// then - 구간이 안 겹치므로 모두 성공
			assertThat(successCount.get()).isEqualTo(numberOfThreads);
		}
	}
}
