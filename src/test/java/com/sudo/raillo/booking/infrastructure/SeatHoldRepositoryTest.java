package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.support.annotation.RedisTest;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

@RedisTest
@DisplayName("SeatHoldRepository 테스트")
class SeatHoldRepositoryTest {

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	@Autowired
	private RedisTemplate<String, String> customStringRedisTemplate;

	private static final Long TRAIN_SCHEDULE_ID = 1001L;
	private static final Long SEAT_ID = 12L;
	private static final Long TRAIN_CAR_ID = 231L;

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
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, departureStopOrder, arrivalStopOrder, TRAIN_CAR_ID
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
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 2, TRAIN_CAR_ID);

			// when - 두 번째 Hold: 2-3, 3-4 구간 (겹치지 않음)
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 4, TRAIN_CAR_ID
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
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 3, TRAIN_CAR_ID);

			// when - 두 번째 Hold: 2-3, 3-4 구간 (2-3 겹침)
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 4, TRAIN_CAR_ID
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
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_001", 0, 5, TRAIN_CAR_ID
			);
			assertThat(result1.success()).isTrue();

			// 2. 단거리 시도 - 겹침 (0-2)
			SeatHoldResult result2 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_002", 0, 2, TRAIN_CAR_ID
			);
			assertThat(result2.success()).isFalse();
			assertThat(result2.isConflictWithHold()).isTrue();

			// 3. 단거리 시도 - 겹침 (3-4)
			SeatHoldResult result3 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_003", 3, 4, TRAIN_CAR_ID
			);
			assertThat(result3.success()).isFalse();

			// 4. 독립 구간 - 성공 (5-10)
			SeatHoldResult result4 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_004", 5, 10, TRAIN_CAR_ID
			);
			assertThat(result4.success()).isTrue();

			// 5. 단거리 시도 - 겹침 (7-8)
			SeatHoldResult result5 = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, "pending_005", 7, 8, TRAIN_CAR_ID
			);
			assertThat(result5.success()).isFalse();
		}
	}

	@Nested
	@DisplayName("Confirm & Release")
	class ConfirmAndReleaseTest {
		@Test
		@DisplayName("Hold 해제 후 같은 구간을 다시 Hold 할 수 있다")
		void release_then_hold_again_success() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";

			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 3, TRAIN_CAR_ID);

			// when
			seatHoldRepository.releaseHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, TRAIN_CAR_ID, 0, 3
			);

			// then
			SeatHoldResult result = seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 0, 3, TRAIN_CAR_ID
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
							TRAIN_SCHEDULE_ID, SEAT_ID, "pending_" + index, 0, 3, TRAIN_CAR_ID
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
							TRAIN_SCHEDULE_ID, SEAT_ID, "pending_" + index, index, index + 1, TRAIN_CAR_ID
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

	@Nested
	@DisplayName("Hold Index 검증")
	class HoldIndexTest {

		@Test
		@DisplayName("Hold 성공 시 Hold Index에 각 구간별 데이터가 추가된다")
		void hold_success_creates_hold_index_entries() {
			// given
			String pendingBookingId = "pending_001";
			int departureStopOrder = 0;
			int arrivalStopOrder = 3;  // 구간: 0-1, 1-2, 2-3
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";

			// when
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, departureStopOrder, arrivalStopOrder, TRAIN_CAR_ID
			);

			// then
			Set<String> members = customStringRedisTemplate.opsForZSet().range(holdIndexKey, 0, -1);
			assertThat(members).hasSize(3);
			assertThat(members.contains("12:0-1")).isTrue();
			assertThat(members.contains("12:1-2")).isTrue();
			assertThat(members.contains("12:2-3")).isTrue();
		}

		@Test
		@DisplayName("Release 시 Hold Index에서 해당 구간 데이터가 제거된다")
		void release_removes_hold_index_entries() {
			// given
			String pendingBookingId = "pending_001";
			int departureStopOrder = 0;
			int arrivalStopOrder = 3;
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";

			// Hold 먼저 수행
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId,
				departureStopOrder, arrivalStopOrder, TRAIN_CAR_ID
			);

			// when
			seatHoldRepository.releaseHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId,
				TRAIN_CAR_ID, departureStopOrder, arrivalStopOrder
			);

			// then
			Set<String> members = customStringRedisTemplate.opsForZSet().range(holdIndexKey, 0, -1);
			assertThat(members).hasSize(0);
		}

		@Test
		@DisplayName("같은 좌석의 서로 다른 구간 Hold 시 Hold Index에 모두 추가된다")
		void different_sections_create_separate_hold_index_entries() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";

			// when - 첫 번째 사용자: 구간 0-1
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 1, TRAIN_CAR_ID
			);

			// 두 번째 사용자: 구간 2-3 (겹치지 않음)
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 3, TRAIN_CAR_ID
			);

			// then
			Set<String> members = customStringRedisTemplate.opsForZSet().range(holdIndexKey, 0, -1);
			assertThat(members).hasSize(2);
			assertThat(members.contains("12:0-1")).isTrue();
			assertThat(members.contains("12:2-3")).isTrue();
		}

		@Test
		@DisplayName("Hold Index의 score는 만료 시간으로 설정된다")
		void hold_index_score_is_expiry_time() {
			// given
			String pendingBookingId = "pending_001";
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";
			long beforeHold = System.currentTimeMillis() / 1000; // 초 단위로 변환

			// when
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, 0, 1, TRAIN_CAR_ID
			);

			long afterHold = System.currentTimeMillis() / 1000; // 초 단위로 변환

			// then
			Double score = customStringRedisTemplate.opsForZSet().score(holdIndexKey, "12:0-1");
			long expectedMinScore = beforeHold + 600; // 600초 (10분)
			long expectedMaxScore = afterHold + 600;

			assertThat(score).isNotNull();
			assertThat(score.longValue()).isBetween(expectedMinScore, expectedMaxScore);
		}

		@Test
		@DisplayName("Hold Index의 TTL은 Hold TTL의 2배로 설정된다")
		void hold_index_ttl_is_double_of_hold_ttl() {
			// given
			String pendingBookingId = "pending_001";
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";

			// when
			seatHoldRepository.tryHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId, 0, 1, TRAIN_CAR_ID
			);

			// then
			Long ttl = customStringRedisTemplate.getExpire(holdIndexKey);
			assertThat(ttl).isGreaterThan(1190L);  // 약간의 오차 허용
			assertThat(ttl).isLessThanOrEqualTo(1200L);
		}

		@Test
		@DisplayName("부분 Release 시 Hold Index에서 해당 구간만 제거된다")
		void partial_release_removes_only_released_sections() {
			// given
			String pendingBookingId1 = "pending_001";
			String pendingBookingId2 = "pending_002";
			String holdIndexKey = "{schedule:1001}:traincar:231:holding-seats";

			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, 0, 1, TRAIN_CAR_ID);
			seatHoldRepository.tryHold(TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId2, 2, 3, TRAIN_CAR_ID);

			// when
			seatHoldRepository.releaseHold(
				TRAIN_SCHEDULE_ID, SEAT_ID, pendingBookingId1, TRAIN_CAR_ID, 0, 1
			);

			// then
			Set<String> members = customStringRedisTemplate.opsForZSet().range(holdIndexKey, 0, -1);
			assertThat(members).hasSize(1);
			assertThat(members.contains("12:2-3")).isTrue();
			assertThat(members.contains("12:0-1")).isFalse();
		}
	}
}
