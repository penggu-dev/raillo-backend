package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.booking.application.dto.SeatOccupancyQuery;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.annotation.RedisTest;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;

@RedisTest
@DisplayName("SeatOccupancyQueryRepository - 검색 구간 점유 좌석 읽기")
class SeatOccupancyQueryRepositoryTest {

	private static final long SCHEDULE_ID = 1001L;
	private static final long CAR_1 = 231L;

	@Autowired
	private SeatOccupancyQueryRepository seatOccupancyQueryRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancies;

	/** 계약을 벗어난 field·값을 일부러 쓰는 오염 테스트 전용. 정상 점유는 {@code seatOccupancies}로 쓴다. */
	private void put(String field, String value) {
		stringRedisTemplate.opsForHash().put(ReservationCacheKey.carSeats(SCHEDULE_ID, CAR_1), field, value);
	}

	private static SeatOccupancyQuery query() {
		return new SeatOccupancyQuery(SCHEDULE_ID, List.of(CAR_1), 1, 3);
	}

	@Test
	@DisplayName("구간이 겹치는 R과 B 좌석을 좌석마다 한 번만 센다")
	void reads_occupied_seats() {
		// given
		seatOccupancies.markReserved(SCHEDULE_ID, CAR_1, 7001L, 1, 3, "RV1");
		seatOccupancies.markBooked(SCHEDULE_ID, CAR_1, 7002L, 2, 3, "55");
		seatOccupancies.markReserved(SCHEDULE_ID, CAR_1, 7003L, 5, 6, "RV9");

		// when
		Map<Long, Set<Long>> occupied = seatOccupancyQueryRepository.findOccupiedSeatIds(query());

		// then 7003은 구간이 겹치지 않아 빠진다
		assertThat(occupied.get(CAR_1)).containsExactlyInAnyOrder(7001L, 7002L);
	}

	@Test
	@DisplayName("반환한 좌석 Set은 호출자가 고칠 수 없다")
	void returns_immutable_sets() {
		// given
		seatOccupancies.markReserved(SCHEDULE_ID, CAR_1, 7001L, 1, 3, "RV1");

		// when
		Map<Long, Set<Long>> occupied = seatOccupancyQueryRepository.findOccupiedSeatIds(query());

		// then 값 비교만 하는 테스트로는 가변 Set이 드러나지 않아 여기서 못박는다
		assertThatThrownBy(() -> occupied.get(CAR_1).add(9999L))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("좌석 점유 값이 아닌 값을 만나면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
	void throws_corrupted_for_unknown_value() {
		// given
		put("7001:1", "Z:bad");

		// when & then 호출자 잘못(400)이 아니라 서버 데이터 오염(500)으로 올려야 한다
		assertThatThrownBy(() -> seatOccupancyQueryRepository.findOccupiedSeatIds(query()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
	}

	@Test
	@DisplayName("field 이름이 좌석 형식이 아니면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
	void throws_corrupted_for_malformed_field_name() {
		// given
		put("7001", "R:RV1");

		// when & then
		assertThatThrownBy(() -> seatOccupancyQueryRepository.findOccupiedSeatIds(query()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
	}

	@Test
	@DisplayName("field의 구간이 숫자가 아니면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
	void throws_corrupted_for_non_numeric_section() {
		// given
		put("7001:x", "R:RV1");

		// when & then
		assertThatThrownBy(() -> seatOccupancyQueryRepository.findOccupiedSeatIds(query()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
	}

	@Test
	@DisplayName("field의 좌석 ID가 숫자가 아니면 SEAT_OCCUPANCY_CORRUPTED 예외가 발생한다")
	void throws_corrupted_for_non_numeric_seat_id() {
		// given
		put("seatA:1", "R:RV1");

		// when & then
		assertThatThrownBy(() -> seatOccupancyQueryRepository.findOccupiedSeatIds(query()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
	}

	@Test
	@DisplayName("객차 목록이 비어 있으면 Redis를 읽지 않고 빈 결과를 돌려준다")
	void returns_empty_without_cars() {
		assertThat(seatOccupancyQueryRepository.findOccupiedSeatIds(
			new SeatOccupancyQuery(SCHEDULE_ID, List.of(), 1, 3))).isEmpty();
	}
}
