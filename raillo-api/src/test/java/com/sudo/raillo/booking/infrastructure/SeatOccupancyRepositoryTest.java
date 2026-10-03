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
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.cache.SeatOccupancyValue;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.annotation.RedisTest;

@RedisTest
@DisplayName("SeatOccupancyRepository - 좌석 점유 스크립트")
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

	private static SeatOccupancyCommand command(String reservationId, int dep, int arr, SeatCar... seats) {
		return command(reservationId, TTL_SECONDS, dep, arr, seats);
	}

	private static SeatOccupancyCommand command(String reservationId, long ttl, int dep, int arr, SeatCar... seats) {
		long expireAt = Instant.now().plusSeconds(3600).getEpochSecond();
		return new SeatOccupancyCommand(SCHEDULE_ID, reservationId, ttl, expireAt, JSON, dep, arr, List.of(seats));
	}

	private String field(long seatId, int section) {
		return ReservationCacheKey.seatField(seatId, section);
	}

	private Map<Object, Object> carHash(long trainCarId) {
		return stringRedisTemplate.opsForHash().entries(ReservationCacheKey.carSeats(SCHEDULE_ID, trainCarId));
	}

	private static BookingOccupancyCommand confirmCommand(String reservationId, long bookingId, int dep, int arr,
		SeatCar... seats) {
		return confirmCommand(reservationId, bookingId, Instant.now().plusSeconds(3600).getEpochSecond(), dep, arr, seats);
	}

	private static BookingOccupancyCommand confirmCommand(String reservationId, long bookingId, long keyExpireAt,
		int dep, int arr, SeatCar... seats) {
		return new BookingOccupancyCommand(SCHEDULE_ID, reservationId, bookingId, keyExpireAt, dep, arr, List.of(seats));
	}

	private static SeatHoldCommand holdCommand(String reservationId, int dep, int arr, SeatCar... seats) {
		long expireAt = Instant.now().plusSeconds(3600).getEpochSecond();
		return new SeatHoldCommand(SCHEDULE_ID, reservationId, expireAt, dep, arr, List.of(seats));
	}

	private static SeatHoldReleaseCommand holdReleaseCommand(String reservationId, int dep, int arr, SeatCar... seats) {
		return new SeatHoldReleaseCommand(SCHEDULE_ID, reservationId, dep, arr, List.of(seats));
	}

	private String orderMarkerKey(String reservationId) {
		return ReservationCacheKey.reservationOrder(SCHEDULE_ID, reservationId);
	}

	/** HTTL 결과. -1은 만료 없음, -2는 field 없음. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private long fieldTtl(long trainCarId, String field) {
		DefaultRedisScript<List> httl = new DefaultRedisScript<>(
			"return redis.call('HTTL', KEYS[1], 'FIELDS', 1, ARGV[1])", List.class);
		List<Object> result = stringRedisTemplate.execute(
			httl, List.of(ReservationCacheKey.carSeats(SCHEDULE_ID, trainCarId)), field);
		return (Long) result.get(0);
	}

	private String carKey(long trainCarId) {
		return ReservationCacheKey.carSeats(SCHEDULE_ID, trainCarId);
	}

	private String reservationKey(String reservationId) {
		return ReservationCacheKey.reservation(SCHEDULE_ID, reservationId);
	}

	@Nested
	@DisplayName("성공")
	class Success {

		@Test
		@DisplayName("빈 좌석은 요청 구간마다 H 값으로 점유되고 예약 본문이 저장된다")
		void holds_every_section_and_stores_reservation() {
			// given - 서울(0) → 부산(3)
			SeatOccupancyCommand command = command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(
				Map.entry(field(SEAT_A, 0), "R:RV1"),
				Map.entry(field(SEAT_A, 1), "R:RV1"),
				Map.entry(field(SEAT_A, 2), "R:RV1"));
			assertThat(stringRedisTemplate.opsForValue().get(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1")))
				.isEqualTo(JSON);
		}

		@Test
		@DisplayName("점유 field와 예약 키는 TTL만큼, 객차 Hash 키는 운행일 기준 만료 시각으로 만료된다")
		void applies_expirations() {
			// given
			SeatOccupancyCommand command = command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1));
			String carKey = ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1);

			// when
			seatOccupancyRepository.occupy(command);

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
			seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(stringRedisTemplate.getExpire(carKey, TimeUnit.SECONDS)).isBetween(95L, 100L);
		}

		@Test
		@DisplayName("여러 객차의 좌석은 각 객차 Hash에 나뉘어 점유된다")
		void holds_across_cars() {
			// given
			SeatOccupancyCommand command = command("RV1", 1, 3,
				new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnlyKeys(field(SEAT_A, 1), field(SEAT_A, 2));
			assertThat(carHash(CAR_2)).containsOnlyKeys(field(SEAT_B, 1), field(SEAT_B, 2));
		}

		@Test
		@DisplayName("같은 좌석이라도 겹치지 않는 구간은 다른 예약이 점유할 수 있다")
		void allows_non_overlapping_sections() {
			// given - RV1: 0→2, RV2: 2→4
			seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command("RV2", 2, 4, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "R:RV1")
				.containsEntry(field(SEAT_A, 2), "R:RV2");
		}

		@Test
		@DisplayName("같은 예약 ID로 다시 실행하면 자기 점유는 충돌로 보지 않는다")
		void is_idempotent_for_same_reservation() {
			// given
			SeatOccupancyCommand command = command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1));
			seatOccupancyRepository.occupy(command);

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command);

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).hasSize(3);
		}

		@Test
		@DisplayName("TTL이 지나면 점유 field와 예약 키가 함께 사라진다")
		void expires_hold_and_reservation() throws InterruptedException {
			// given
			String carKey = ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1);
			seatOccupancyRepository.occupy(command("RV1", 1L, 0, 2, new SeatCar(SEAT_A, CAR_1)));

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
			seatOccupancyRepository.occupy(command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command("RV2", 2, 4, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithReservation()).isTrue();
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
				field(SEAT_A, 1), SeatOccupancyValue.booked("77").serialize());

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command("RV1", 0, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithBooking()).isTrue();
			assertThat(result.conflictSeatId()).isEqualTo(SEAT_A);
			assertThat(result.conflictSectionIndex()).isEqualTo(1);
			assertThat(carHash(CAR_1)).hasSize(1);
		}

		@Test
		@DisplayName("두 번째 객차에서 충돌하면 첫 번째 객차도 점유되지 않는다")
		void is_atomic_across_cars() {
			// given
			stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_2),
				field(SEAT_B, 0), "R:OTHER");

			// when
			SeatOccupancyResult result = seatOccupancyRepository.occupy(command("RV1", 0, 2,
				new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(carHash(CAR_1)).isEmpty();
			assertThat(carHash(CAR_2)).hasSize(1);
		}

		@Test
		@DisplayName("객차 키가 Hash가 아니면 오염이 아니라 SEAT_OCCUPANCY_SCRIPT_ERROR 예외가 발생한다")
		void throws_script_error_when_car_key_is_not_hash() {
			// given - 스크립트 실행 자체가 실패하는 상황(WRONGTYPE)
			stringRedisTemplate.opsForValue().set(carKey(CAR_1), "not-a-hash");

			// when & then
			assertThatThrownBy(() -> seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}

		@Test
		@DisplayName("알 수 없는 형식의 점유 값을 만나면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
		void rejects_unknown_value() {
			// given
			stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1),
				field(SEAT_A, 0), "X:1");

			// when

			// then
			assertThatThrownBy(() -> seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
		}
	}

	@Nested
	@DisplayName("결제 중 좌석 보호")
	class PaymentHold {

		@Test
		@DisplayName("자기 예약이 점유한 field는 만료가 사라져 예약 TTL이 지나도 남는다")
		void persists_own_reservation_fields() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isEqualTo(-1L);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 2))).isEqualTo(-1L);
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "R:RV1");
		}

		@Test
		@DisplayName("보호가 풀려 사라진 자기 field는 다시 점유되고 만료 없이 남는다")
		void reclaims_missing_fields() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.opsForHash().delete(carKey(CAR_1), field(SEAT_A, 2));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 2), "R:RV1");
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 2))).isEqualTo(-1L);
		}

		@Test
		@DisplayName("다른 예약이 점유한 구간이 있으면 예약 충돌을 돌려주고 아무것도 바꾸지 않는다")
		void conflicts_with_other_reservation() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.occupy(command("RV2", 2, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.conflictSeatId()).isEqualTo(SEAT_A);
			assertThat(result.conflictSectionIndex()).isEqualTo(2);
			assertThat(result.conflictType()).isEqualTo(SeatOccupancyValue.Type.RESERVED);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isGreaterThan(0L);
		}

		@Test
		@DisplayName("이미 예매로 전환된 구간이 있으면 예매 충돌을 돌려주고 아무것도 바꾸지 않는다")
		void conflicts_with_booked_field() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 7001L, 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.conflictType()).isEqualTo(SeatOccupancyValue.Type.BOOKED);
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "B:7001");
		}

		@Test
		@DisplayName("두 번째 객차에서 충돌하면 첫 번째 객차의 만료도 그대로 남는다")
		void is_atomic_across_cars() {
			// given
			seatOccupancyRepository.occupy(
				command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));
			stringRedisTemplate.opsForHash().put(carKey(CAR_2), field(SEAT_B, 1), "R:RV9");

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isGreaterThan(0L);
		}

		@Test
		@DisplayName("만료가 없는 객차 Hash 키에는 운행일 기준 만료를 건다")
		void applies_car_key_expiration_when_absent() {
			// given
			long keyExpireAt = Instant.now().plusSeconds(3600).getEpochSecond();
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 1), "R:RV1");

			// when
			seatOccupancyRepository.hold(new SeatHoldCommand(
				SCHEDULE_ID, "RV1", keyExpireAt, 1, 2, List.of(new SeatCar(SEAT_A, CAR_1))));

			// then
			assertThat(stringRedisTemplate.getExpire(carKey(CAR_1), TimeUnit.SECONDS)).isGreaterThan(0L);
		}

		@Test
		@DisplayName("객차 Hash 키에 이미 만료가 걸려 있으면 덮어쓰지 않는다")
		void keeps_existing_car_key_expiration() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.expire(carKey(CAR_1), 100L, TimeUnit.SECONDS);

			// when - holdCommand는 3600초 뒤 만료를 넘긴다
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(stringRedisTemplate.getExpire(carKey(CAR_1), TimeUnit.SECONDS)).isBetween(95L, 100L);
		}

		@Test
		@DisplayName("같은 예약으로 다시 실행해도 결과가 같다")
		void is_idempotent() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isEqualTo(-1L);
		}

		@Test
		@DisplayName("객차 키가 Hash가 아니면 오염이 아니라 SEAT_OCCUPANCY_SCRIPT_ERROR 예외가 발생한다")
		void throws_script_error_when_car_key_is_not_hash() {
			// given - 스크립트 실행 자체가 실패하는 상황(WRONGTYPE)
			stringRedisTemplate.opsForValue().set(carKey(CAR_1), "not-a-hash");

			// when & then
			assertThatThrownBy(() -> seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}

		@Test
		@DisplayName("알 수 없는 형식의 점유 값을 만나면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
		void rejects_unknown_value() {
			// given
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 1), "Z:bad");

			// when & then
			assertThatThrownBy(() -> seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
		}
	}

	@Nested
	@DisplayName("결제 중 좌석 보호 해제")
	class PaymentRelease {

		@Test
		@DisplayName("주문 표시가 없으면 예약 본문의 남은 수명만큼 만료가 되돌아온다")
		void restores_from_reservation_body() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isEqualTo(2);
			assertThat(result.deletedCount()).isZero();
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isBetween(1L, TTL_SECONDS);
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "R:RV1");
		}

		@Test
		@DisplayName("주문 표시가 살아 있으면 예약 본문보다 표시의 남은 수명을 쓴다")
		void prefers_order_marker_deadline() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 60L, 1, 2, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.opsForValue().set(orderMarkerKey("RV1"), "ORD1", 600, TimeUnit.SECONDS);

			// when
			seatOccupancyRepository.releaseHold(holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isGreaterThan(60L);
		}

		@Test
		@DisplayName("주문 표시도 예약 본문도 없으면 자기 field를 삭제한다")
		void deletes_fields_when_no_deadline_remains() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.delete(reservationKey("RV1"));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isZero();
			assertThat(result.deletedCount()).isEqualTo(2);
			assertThat(carHash(CAR_1)).doesNotContainKey(field(SEAT_A, 1));
		}

		@Test
		@DisplayName("되돌릴 기한이 남아 있어도 자기 것이 아닌 field에는 만료를 걸지 않는다")
		void restores_only_own_fields_when_deadline_remains() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 2), "R:RV9");

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isEqualTo(1);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isBetween(1L, TTL_SECONDS);
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 2), "R:RV9");
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 2))).isEqualTo(-1L);
		}

		@Test
		@DisplayName("다른 예약이 점유한 field는 건드리지 않는다")
		void skips_fields_owned_by_others() {
			// given
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 1), "R:RV9");

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isZero();
			assertThat(result.deletedCount()).isZero();
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "R:RV9");
		}

		@Test
		@DisplayName("이미 예매로 전환된 자기 좌석은 건드리지 않는다")
		void skips_already_booked_fields() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 7001L, 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 3, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isZero();
			assertThat(result.deletedCount()).isZero();
			assertThat(carHash(CAR_1)).containsEntry(field(SEAT_A, 1), "B:7001");
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isEqualTo(-1L);
		}

		@Test
		@DisplayName("두 객차에 걸친 예약은 두 객차 모두 되돌아온다")
		void restores_across_two_cars() {
			// given
			seatOccupancyRepository.occupy(
				command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));
			seatOccupancyRepository.hold(
				holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.restoredCount()).isEqualTo(2);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isGreaterThan(0L);
			assertThat(fieldTtl(CAR_2, field(SEAT_B, 1))).isGreaterThan(0L);
		}

		@Test
		@DisplayName("보호하지 않은 예약에 실행해도 남은 만료를 유지한 채 끝난다")
		void is_safe_without_prior_hold() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isEqualTo(1);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isBetween(1L, TTL_SECONDS);
		}

		@Test
		@DisplayName("같은 예약으로 다시 실행해도 결과가 같다")
		void is_idempotent() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.hold(holdCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.releaseHold(holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatHoldReleaseResult result = seatOccupancyRepository.releaseHold(
				holdReleaseCommand("RV1", 1, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.restoredCount()).isEqualTo(1);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isBetween(1L, TTL_SECONDS);
		}
	}

	@Nested
	@DisplayName("예매 점유 전환")
	class ConfirmBooking {

		@Test
		@DisplayName("자기 예약 점유는 예매 점유로 바뀌고 field 만료가 없어지며 예약 본문이 삭제된다")
		void converts_own_reservation_to_booking() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(
				Map.entry(field(SEAT_A, 0), "B:77"),
				Map.entry(field(SEAT_A, 1), "B:77"));
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 0))).isEqualTo(-1L);
			assertThat(fieldTtl(CAR_1, field(SEAT_A, 1))).isEqualTo(-1L);
			assertThat(stringRedisTemplate.hasKey(reservationKey("RV1"))).isFalse();
		}

		@Test
		@DisplayName("두 객차에 걸친 예약도 두 객차 모두 예매 점유로 바뀐다")
		void converts_seats_across_two_cars() {
			// given
			seatOccupancyRepository.occupy(
				command("RV1", 0, 1, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 1, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(Map.entry(field(SEAT_A, 0), "B:77"));
			assertThat(carHash(CAR_2)).containsOnly(Map.entry(field(SEAT_B, 0), "B:77"));
		}

		@Test
		@DisplayName("이미 같은 예매로 전환된 좌석은 다시 실행해도 성공한다")
		void is_idempotent_for_same_booking() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1)));
			seatOccupancyRepository.confirmBooking(confirmCommand("RV1", 77L, 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 2, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(
				Map.entry(field(SEAT_A, 0), "B:77"),
				Map.entry(field(SEAT_A, 1), "B:77"));
		}

		@Test
		@DisplayName("비어 있는 구간에는 예매 점유를 쓰고 만료가 없는 객차 키에 운행일 만료를 건다")
		void writes_booking_into_empty_sections() {
			// given - 아무 점유도 없다

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 1, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(Map.entry(field(SEAT_A, 0), "B:77"));
			assertThat(stringRedisTemplate.getExpire(carKey(CAR_1), TimeUnit.SECONDS)).isPositive();
		}

		@Test
		@DisplayName("객차 키에 이미 만료가 있으면 그 만료를 바꾸지 않는다")
		void keeps_existing_car_key_expiration() {
			// given - occupy가 지금부터 1시간 뒤 만료를 건다
			seatOccupancyRepository.occupy(command("RV1", 0, 1, new SeatCar(SEAT_A, CAR_1)));
			long laterExpireAt = Instant.now().plusSeconds(7200).getEpochSecond();

			// when
			seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, laterExpireAt, 0, 1, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(stringRedisTemplate.getExpire(carKey(CAR_1), TimeUnit.SECONDS)).isLessThanOrEqualTo(3600L);
		}

		@Test
		@DisplayName("한 구간이라도 다른 예약이 점유하면 다른 객차를 포함해 아무것도 바꾸지 않고 충돌을 돌려준다")
		void does_nothing_when_any_section_is_taken() {
			// given
			seatOccupancyRepository.occupy(
				command("RV1", 0, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));
			stringRedisTemplate.opsForHash().put(carKey(CAR_2), field(SEAT_B, 1), "R:RV2");

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 2, new SeatCar(SEAT_A, CAR_1), new SeatCar(SEAT_B, CAR_2)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.conflictSeatId()).isEqualTo(SEAT_B);
			assertThat(result.conflictSectionIndex()).isEqualTo(1);
			assertThat(result.isConflictWithReservation()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(
				Map.entry(field(SEAT_A, 0), "R:RV1"),
				Map.entry(field(SEAT_A, 1), "R:RV1"));
			assertThat(stringRedisTemplate.hasKey(reservationKey("RV1"))).isTrue();
		}

		@Test
		@DisplayName("다른 예매가 점유한 구간이면 예매 충돌을 돌려준다")
		void returns_booking_conflict_for_other_booking() {
			// given
			seatOccupancyRepository.occupy(command("RV1", 0, 1, new SeatCar(SEAT_A, CAR_1)));
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 0), "B:99");

			// when
			SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 1, new SeatCar(SEAT_A, CAR_1)));

			// then
			assertThat(result.success()).isFalse();
			assertThat(result.isConflictWithBooking()).isTrue();
			assertThat(carHash(CAR_1)).containsOnly(Map.entry(field(SEAT_A, 0), "B:99"));
		}

		@Test
		@DisplayName("객차 키가 Hash가 아니면 오염이 아니라 SEAT_OCCUPANCY_SCRIPT_ERROR 예외가 발생한다")
		void throws_script_error_when_car_key_is_not_hash() {
			// given - 스크립트 실행 자체가 실패하는 상황(WRONGTYPE)
			stringRedisTemplate.opsForValue().set(carKey(CAR_1), "not-a-hash");

			// when & then
			assertThatThrownBy(() -> seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 1, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}

		@Test
		@DisplayName("좌석 값이 알 수 없는 형식이면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
		void throws_when_value_format_is_unknown() {
			// given
			stringRedisTemplate.opsForHash().put(carKey(CAR_1), field(SEAT_A, 0), "Z:1");

			// when & then
			assertThatThrownBy(() -> seatOccupancyRepository.confirmBooking(
				confirmCommand("RV1", 77L, 0, 1, new SeatCar(SEAT_A, CAR_1))))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
		}
	}
}
