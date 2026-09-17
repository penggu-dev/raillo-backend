package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.cache.SeatOccupancyValue;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyHoldCommand.SeatCar;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.annotation.RedisTest;

@RedisTest
@DisplayName("SeatOccupancyRepository - 좌석 점유 생성 스크립트")
class SeatOccupancyRepositoryTest {

	private static final long SCHEDULE_ID = 1001L;
	private static final long CAR_1 = 231L;
	private static final long CAR_2 = 232L;
	private static final long SEAT_A = 12L;
	private static final long SEAT_B = 13L;
	private static final long TTL_SECONDS = 600L;
	private static final String JSON = "{\"reservationId\":\"RV1\"}";

	@Autowired
	private SeatOccupancyRepository seatOccupancyRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	private static SeatOccupancyHoldCommand command(String reservationId, int dep, int arr, SeatCar... seats) {
		return command(reservationId, TTL_SECONDS, dep, arr, seats);
	}

	private static SeatOccupancyHoldCommand command(String reservationId, long ttl, int dep, int arr, SeatCar... seats) {
		long expireAt = Instant.now().plusSeconds(3600).getEpochSecond();
		return new SeatOccupancyHoldCommand(SCHEDULE_ID, reservationId, ttl, expireAt, JSON, dep, arr, List.of(seats));
	}

	private String field(long seatId, int section) {
		return ReservationCacheKey.seatField(seatId, section);
	}

	private Map<Object, Object> carHash(long trainCarId) {
		return stringRedisTemplate.opsForHash().entries(ReservationCacheKey.carSeats(SCHEDULE_ID, trainCarId));
	}

	@Nested
	@DisplayName("성공")
	class Success {

		@Test
		@DisplayName("빈 좌석은 요청 구간마다 H 값으로 점유되고 예약 본문이 저장된다")
		void holds_every_section_and_stores_reservation() {
			// given - 서울(0) → 부산(3)
			SeatOccupancyHoldCommand command = command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(
				Map.entry(field(SEAT_A, 0), "H:RV1"),
				Map.entry(field(SEAT_A, 1), "H:RV1"),
				Map.entry(field(SEAT_A, 2), "H:RV1"));
			assertThat(stringRedisTemplate.opsForValue().get(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1")))
				.isEqualTo(JSON);
		}

		@Test
		@DisplayName("점유 field와 예약 키는 TTL만큼, 객차 Hash 키는 운행일 기준 만료 시각으로 만료된다")
		void applies_expirations() {
			// given
			SeatOccupancyHoldCommand command = command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1));
			String carKey = ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1);

			// when
			seatOccupancyRepository.hold(command);

			// then
			var fieldTtls = stringRedisTemplate.opsForHash()
				.getTimeToLive(carKey, TimeUnit.SECONDS, List.of(field(SEAT_A, 0), field(SEAT_A, 1)));
			assertThat(fieldTtls.ttlOf(field(SEAT_A, 0)).getSeconds()).isBetween(TTL_SECONDS - 5, TTL_SECONDS);
			assertThat(fieldTtls.ttlOf(field(SEAT_A, 1)).getSeconds()).isBetween(TTL_SECONDS - 5, TTL_SECONDS);

			Long reservationTtl = stringRedisTemplate.getExpire(
				ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"), TimeUnit.SECONDS);
			assertThat(reservationTtl).isBetween(TTL_SECONDS - 5, TTL_SECONDS);

			Long carKeyTtl = stringRedisTemplate.getExpire(carKey, TimeUnit.SECONDS);
			assertThat(carKeyTtl).isBetween(3600L - 5, 3600L);
		}

		@Test
		@DisplayName("객차 Hash 키에 이미 만료가 걸려 있으면 덮어쓰지 않는다")
		void keeps_existing_key_expiration() {
			// given
			String carKey = ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1);
			stringRedisTemplate.opsForHash().put(carKey, field(99L, 0), "B:1");
			stringRedisTemplate.expire(carKey, 100L, TimeUnit.SECONDS);

			// when
			seatOccupancyRepository.hold(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(stringRedisTemplate.getExpire(carKey, TimeUnit.SECONDS)).isBetween(95L, 100L);
		}

		@Test
		@DisplayName("여러 객차의 좌석은 각 객차 Hash에 나뉘어 점유된다")
		void holds_across_cars() {
			// given
			SeatOccupancyHoldCommand command = command("RV1", 1, 3,
				new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnlyKeys(field(SEAT_A, 1), field(SEAT_A, 2));
			assertThat(carHash(CAR_2)).containsOnlyKeys(field(SEAT_B, 1), field(SEAT_B, 2));
		}

		@Test
		@DisplayName("같은 좌석이라도 겹치지 않는 구간은 다른 예약이 점유할 수 있다")
		void allows_non_overlapping_sections() {
			// given - RV1: 0→2, RV2: 2→4
			seatOccupancyRepository.hold(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command("RV2", 2, 4, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "H:RV1")
				.containsEntry(field(SEAT_A, 2), "H:RV2");
		}

		@Test
		@DisplayName("같은 예약 ID로 다시 실행하면 자기 점유는 충돌로 보지 않는다")
		void is_idempotent_for_same_reservation() {
			// given
			SeatOccupancyHoldCommand command = command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1));
			seatOccupancyRepository.hold(command);

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).hasSize(3);
		}

		@Test
		@DisplayName("TTL이 지나면 점유 field와 예약 키가 함께 사라진다")
		void expires_hold_and_reservation() throws InterruptedException {
			// given
			String carKey = ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1);
			seatOccupancyRepository.hold(command("RV1", 1L, 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			Thread.sleep(1500);

			// then
			assertThat(stringRedisTemplate.opsForHash().entries(carKey)).isEmpty();
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isFalse();
		}
	}

	@Nested
	@DisplayName("충돌")
	class Conflict {

		@Test
		@DisplayName("다른 예약이 임시 점유한 구간과 겹치면 H 충돌을 돌려주고 아무것도 쓰지 않는다")
		void conflicts_with_hold() {
			// given - RV1: 0→3, RV2: 2→4 (구간 2 겹침)
			seatOccupancyRepository.hold(command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command("RV2", 2, 4, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithHold()).isTrue();
			assertThat(result.conflictSeatId()).isEqualTo(SEAT_A);
			assertThat(result.conflictSectionIndex()).isEqualTo(2);
			assertThat(carHash(CAR_1)).doesNotContainKey(field(SEAT_A, 3));
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV2"))).isFalse();
		}

		@Test
		@DisplayName("이미 판매된 구간과 겹치면 B 충돌을 돌려준다")
		void conflicts_with_sold() {
			// given
			stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1),
				field(SEAT_A, 1), SeatOccupancyValue.sold("77").serialize());

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithSold()).isTrue();
			assertThat(result.conflictSeatId()).isEqualTo(SEAT_A);
			assertThat(result.conflictSectionIndex()).isEqualTo(1);
			assertThat(carHash(CAR_1)).hasSize(1);
		}

		@Test
		@DisplayName("두 번째 객차에서 충돌하면 첫 번째 객차도 점유되지 않는다")
		void is_atomic_across_cars() {
			// given
			stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_2),
				field(SEAT_B, 0), "H:OTHER");

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(command("RV1", 0, 2,
				new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(carHash(CAR_1)).isEmpty();
			assertThat(carHash(CAR_2)).hasSize(1);
		}

		@Test
		@DisplayName("알 수 없는 형식의 점유 값을 만나면 SEAT_HOLD_SCRIPT_ERROR 예외가 발생한다")
		void rejects_unknown_value() {
			// given
			stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1),
				field(SEAT_A, 0), "X:1");

			// when

			// then
			assertThatThrownBy(() -> seatOccupancyRepository.hold(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}
}
