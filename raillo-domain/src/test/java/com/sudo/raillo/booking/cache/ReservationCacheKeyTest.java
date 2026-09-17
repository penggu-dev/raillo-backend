package com.sudo.raillo.booking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.train.cache.TrainCacheKey;

@DisplayName("ReservationCacheKey - 예약·좌석 점유 Redis 키 계약")
class ReservationCacheKeyTest {

	@Nested
	@DisplayName("운행 단위 키")
	class ScheduleKeys {

		@Test
		@DisplayName("좌석 점유 키는 운행과 객차 ID로 만들어진다")
		void builds_car_seats_key() {
			// given

			// when
			String key = ReservationCacheKey.carSeats(1001L, 231L);

			// then
			assertThat(key).isEqualTo("{schedule:1001}:car:231:seats");
		}

		@Test
		@DisplayName("예약 키는 운행 ID와 예약 ID로 만들어진다")
		void builds_reservation_key() {
			// given

			// when
			String key = ReservationCacheKey.reservation(1001L, "RV20260917120000ABC123");

			// then
			assertThat(key).isEqualTo("{schedule:1001}:reservation:RV20260917120000ABC123");
		}

		@Test
		@DisplayName("운행 단위 키는 기준정보 캐시와 같은 hash tag를 써서 같은 slot에 놓인다")
		void shares_hash_tag_with_train_cache() {
			// given
			long trainScheduleId = 1001L;
			String hashTag = TrainCacheKey.scheduleInfo(trainScheduleId).substring(0, "{schedule:1001}:".length());

			// when

			// then
			assertThat(ReservationCacheKey.carSeats(trainScheduleId, 1L)).startsWith(hashTag);
			assertThat(ReservationCacheKey.reservation(trainScheduleId, "RV1")).startsWith(hashTag);
		}
	}

	@Nested
	@DisplayName("회원 인덱스 키")
	class MemberKeys {

		@Test
		@DisplayName("회원 인덱스는 회원번호로 만들어지고 운행 hash tag를 쓰지 않는다")
		void builds_member_key_without_schedule_tag() {
			// given

			// when
			String key = ReservationCacheKey.memberReservations("202601010001");

			// then
			assertThat(key).isEqualTo("member:202601010001:reservations");
			assertThat(key).doesNotContain("{schedule:");
		}
	}

	@Nested
	@DisplayName("좌석 field와 구간")
	class SeatFields {

		@Test
		@DisplayName("좌석 field는 좌석 ID와 구간 index를 이어 붙인다")
		void builds_seat_field() {
			// given

			// when
			String field = ReservationCacheKey.seatField(46456L, 3);

			// then
			assertThat(field).isEqualTo("46456:3");
		}

		@Test
		@DisplayName("구간 index는 출발 stopOrder부터 도착 stopOrder 직전까지다")
		void sections_are_half_open_range() {
			// given - 서울(0) → 대전(1) → 동대구(2) → 부산(3)

			// when

			// then
			assertThat(ReservationCacheKey.sectionIndices(0, 3)).containsExactly(0, 1, 2);
			assertThat(ReservationCacheKey.sectionIndices(1, 2)).containsExactly(1);
		}

		@Test
		@DisplayName("출발이 도착보다 앞서지 않으면 거부한다")
		void rejects_non_forward_range() {
			// given

			// when

			// then
			assertThatThrownBy(() -> ReservationCacheKey.sectionIndices(2, 2))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ReservationCacheKey.sectionIndices(3, 1))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
