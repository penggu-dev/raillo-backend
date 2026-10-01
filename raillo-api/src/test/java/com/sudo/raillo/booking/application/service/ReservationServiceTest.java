package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.ReservationFixture;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;

@ServiceTest
@DisplayName("ReservationService - 예약 실행과 조회")
class ReservationServiceTest {

	private static final String MEMBER_NO = "202507300001";
	private static final long SCHEDULE_ID = 1001L;
	private static final long CAR_ID = 231L;
	private static final Duration TTL = Duration.ofMinutes(10);

	@Autowired
	private ReservationService reservationService;

	@MockitoSpyBean
	private ReservationRedisRepository reservationRedisRepository;

	@MockitoSpyBean
	private SeatOccupancyRepository seatOccupancyRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private RedisJsonConverter redisJsonConverter;

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancyTestHelper;

	private static Reservation reservation(String reservationId, Long... seatIds) {
		return ReservationFixture.builder()
			.withReservationId(reservationId)
			.withMemberNo(MEMBER_NO)
			.withTrainScheduleId(SCHEDULE_ID)
			.withSeatIds(CAR_ID, seatIds)
			.build();
	}

	private Object memberIndexValue(String reservationId) {
		return stringRedisTemplate.opsForHash().get(ReservationCacheKey.memberReservations(MEMBER_NO), reservationId);
	}

	@Nested
	@DisplayName("reserve")
	class Reserve {

		@Test
		@DisplayName("예약에 성공하면 회원 인덱스, 좌석 점유 field, 예약 본문이 모두 남는다")
		void reserve_success() {
			// given - 서울(0) → 부산(1), 좌석 2개
			Reservation reservation = reservation("RV1", 11L, 12L);

			// when
			reservationService.reserve(reservation, TTL);

			// then
			assertThat(memberIndexValue("RV1")).isEqualTo(String.valueOf(SCHEDULE_ID));
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("R:RV1");
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 12L, 0)).isEqualTo("R:RV1");

			String json = stringRedisTemplate.opsForValue().get(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"));
			assertThat(redisJsonConverter.fromJson(json, Reservation.class)).isEqualTo(reservation);
			assertThat(stringRedisTemplate.getExpire(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"), TimeUnit.SECONDS))
				.isBetween(595L, 600L);
		}

		@Test
		@DisplayName("TTL이 정수 초가 아니면 올림해서 적용한다")
		void rounds_ttl_up() {
			// given
			Reservation reservation = reservation("RV1", 11L);

			// when
			reservationService.reserve(reservation, Duration.ofMillis(1_200));

			// then - 1.2초 → 2초
			assertThat(stringRedisTemplate.getExpire(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"), TimeUnit.SECONDS))
				.isBetween(1L, 2L);
		}

		@Test
		@DisplayName("다른 예약이 점유한 구간이면 SEAT_CONFLICT_WITH_RESERVATION 예외가 발생하고 회원 인덱스는 남지 않는다")
		void conflict_with_hold() {
			// given
			seatOccupancyTestHelper.markReserved(SCHEDULE_ID, CAR_ID, 11L, 0, 1, "OTHER");
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_RESERVATION);
			assertThat(memberIndexValue("RV1")).isNull();
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isFalse();
		}

		@Test
		@DisplayName("이미 판매된 구간이면 SEAT_CONFLICT_WITH_BOOKING 예외가 발생하고 회원 인덱스는 남지 않는다")
		void conflict_with_sold() {
			// given
			seatOccupancyTestHelper.markBooked(SCHEDULE_ID, CAR_ID, 11L, 0, 1, "77");
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_BOOKING);
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("점유 스크립트가 실패하면 예외가 전파되고 회원 인덱스는 되돌려진다")
		void script_failure_rolls_back_index() {
			// given
			doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR))
				.when(seatOccupancyRepository).occupy(any());
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("점유 스크립트가 저장을 마친 뒤 응답만 실패하면 예약을 성공으로 처리하고 회원 인덱스를 유지한다")
		void keeps_reservation_when_script_stored_but_response_failed() {
			// given - 스크립트는 실행되고 클라이언트에는 오류가 돌아온 상황
			doAnswer(invocation -> {
				invocation.callRealMethod();
				throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
			}).when(seatOccupancyRepository).occupy(any());
			Reservation reservation = reservation("RV1", 11L);

			// when
			reservationService.reserve(reservation, TTL);

			// then
			assertThat(memberIndexValue("RV1")).isEqualTo(String.valueOf(SCHEDULE_ID));
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isTrue();
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("R:RV1");
		}

		@Test
		@DisplayName("점유 스크립트가 실패하고 저장 여부 확인도 실패하면 회원 인덱스를 되돌리고 원래 예외를 던진다")
		void rolls_back_index_when_store_check_fails() {
			// given
			doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR))
				.when(seatOccupancyRepository).occupy(any());
			doThrow(new QueryTimeoutException("redis timeout"))
				.when(reservationRedisRepository).exists(anyLong(), anyString());
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("회원 인덱스 등록이 실패하면 좌석 점유를 시도하지 않는다")
		void index_failure_skips_hold() {
			// given
			doThrow(new QueryTimeoutException("redis timeout"))
				.when(reservationRedisRepository).indexForMember(anyString(), anyString(), anyLong(), any());
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(QueryTimeoutException.class);
			assertThat(seatOccupancyTestHelper.entries(SCHEDULE_ID, CAR_ID)).isEmpty();
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isFalse();
		}
	}

	@Nested
	@DisplayName("getReservations")
	class GetReservations {

		@Test
		@DisplayName("회원의 예약을 요청한 순서대로 돌려준다")
		void returns_in_request_order() {
			// given
			Reservation first = reservationTestHelper.save(reservation("RV1", 11L));
			Reservation second = reservationTestHelper.save(
				ReservationFixture.builder().withReservationId("RV2").withMemberNo(MEMBER_NO)
					.withTrainScheduleId(1002L).withSeatIds(CAR_ID, 21L).build());

			// when
			List<Reservation> reservations = reservationService.getReservations(List.of("RV2", "RV1"), MEMBER_NO);

			// then
			assertThat(reservations).containsExactly(second, first);
		}

		@Test
		@DisplayName("회원 인덱스에 없는 예약이 있으면 RESERVATION_EXPIRED 예외가 발생한다")
		void missing_index() {
			// given
			reservationTestHelper.save(reservation("RV1", 11L));

			// when

			// then
			assertThatThrownBy(() -> reservationService.getReservations(List.of("RV1", "RV9"), MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}

		@Test
		@DisplayName("인덱스는 있지만 본문이 만료됐으면 RESERVATION_EXPIRED 예외가 발생한다")
		void missing_body() {
			// given
			reservationTestHelper.saveIndexOnly(reservation("RV1", 11L));

			// when

			// then
			assertThatThrownBy(() -> reservationService.getReservations(List.of("RV1"), MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}

		@Test
		@DisplayName("다른 회원의 예약 ID로는 조회할 수 없다")
		void other_member() {
			// given
			reservationTestHelper.save(reservation("RV1", 11L));

			// when

			// then - 다른 회원의 인덱스에는 없으므로 만료와 같이 처리된다
			assertThatThrownBy(() -> reservationService.getReservations(List.of("RV1"), "202507300002"))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED);
		}

		@Test
		@DisplayName("예약 ID 목록이 비어 있으면 RESERVATION_IDS_REQUIRED 예외가 발생한다")
		void empty_ids() {
			// given

			// when

			// then
			assertThatThrownBy(() -> reservationService.getReservations(List.of(), MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_IDS_REQUIRED);
		}
	}

	@Nested
	@DisplayName("getMyReservations")
	class GetMyReservations {

		@Test
		@DisplayName("내 예약을 생성 시각 순으로 돌려준다")
		void returns_in_created_order() {
			// given
			LocalDateTime base = LocalDateTime.of(2026, 9, 17, 12, 0, 0);
			Reservation later = reservationTestHelper.save(
				ReservationFixture.builder().withReservationId("RV2").withMemberNo(MEMBER_NO)
					.withTrainScheduleId(SCHEDULE_ID).withSeatIds(CAR_ID, 12L).withCreatedAt(base.plusMinutes(1)).build());
			Reservation earlier = reservationTestHelper.save(
				ReservationFixture.builder().withReservationId("RV1").withMemberNo(MEMBER_NO)
					.withTrainScheduleId(1002L).withSeatIds(CAR_ID, 11L).withCreatedAt(base).build());

			// when
			List<Reservation> reservations = reservationService.getMyReservations(MEMBER_NO);

			// then
			assertThat(reservations).containsExactly(earlier, later);
		}

		@Test
		@DisplayName("다른 회원의 예약은 포함하지 않는다")
		void excludes_other_member() {
			// given
			Reservation mine = reservationTestHelper.save(reservation("RV1", 11L));
			reservationTestHelper.save(ReservationFixture.builder().withReservationId("RV2")
				.withMemberNo("202507300002").withTrainScheduleId(SCHEDULE_ID).withSeatIds(CAR_ID, 12L).build());

			// when
			List<Reservation> reservations = reservationService.getMyReservations(MEMBER_NO);

			// then
			assertThat(reservations).containsExactly(mine);
		}

		@Test
		@DisplayName("본문이 만료된 인덱스는 제외한다")
		void excludes_expired_body() {
			// given
			Reservation alive = reservationTestHelper.save(reservation("RV1", 11L));
			reservationTestHelper.saveIndexOnly(reservation("RV2", 12L));

			// when
			List<Reservation> reservations = reservationService.getMyReservations(MEMBER_NO);

			// then
			assertThat(reservations).containsExactly(alive);
		}

		@Test
		@DisplayName("예약이 없으면 빈 목록을 돌려준다")
		void empty() {
			// given

			// when
			List<Reservation> reservations = reservationService.getMyReservations(MEMBER_NO);

			// then
			assertThat(reservations).isEmpty();
		}
	}

	@Nested
	@DisplayName("cancel")
	class Cancel {

		@Test
		@DisplayName("예약을 삭제하면 점유 field, 예약 본문, 회원 인덱스가 모두 사라진다")
		void cancel_success() {
			// given
			reservationService.reserve(reservation("RV1", 11L, 12L), TTL);

			// when
			reservationService.cancel("RV1", MEMBER_NO);

			// then
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isNull();
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 12L, 0)).isNull();
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isFalse();
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("삭제한 좌석은 다른 예약이 바로 잡을 수 있다")
		void seat_is_reservable_after_cancel() {
			// given
			reservationService.reserve(reservation("RV1", 11L), TTL);
			reservationService.cancel("RV1", MEMBER_NO);

			// when
			reservationService.reserve(ReservationFixture.builder().withReservationId("RV2")
				.withMemberNo("202507300002").withTrainScheduleId(SCHEDULE_ID).withSeatIds(CAR_ID, 11L).build(), TTL);

			// then
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("R:RV2");
		}

		@Test
		@DisplayName("내 예약이 아닌 점유(다른 예약, 예매)는 건드리지 않는다")
		void keeps_other_occupancy() {
			// given - RV1이 잡은 좌석과 다른 좌석에 다른 예약 R:과 예매 B:가 있다
			reservationService.reserve(reservation("RV1", 11L), TTL);
			seatOccupancyTestHelper.markReserved(SCHEDULE_ID, CAR_ID, 12L, 0, 1, "OTHER");
			seatOccupancyTestHelper.markBooked(SCHEDULE_ID, CAR_ID, 13L, 0, 1, "77");

			// when
			reservationService.cancel("RV1", MEMBER_NO);

			// then
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 12L, 0)).isEqualTo("R:OTHER");
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 13L, 0)).isEqualTo("B:77");
		}

		@Test
		@DisplayName("이미 만료됐거나 없는 예약은 아무 일 없이 성공한다")
		void idempotent_when_absent() {
			// given

			// when

			// then
			reservationService.cancel("RV9", MEMBER_NO);
		}

		@Test
		@DisplayName("본문이 먼저 만료됐으면 남은 회원 인덱스만 정리한다")
		void cleans_index_when_body_expired() {
			// given
			reservationTestHelper.saveIndexOnly(reservation("RV1", 11L));

			// when
			reservationService.cancel("RV1", MEMBER_NO);

			// then
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("다른 회원의 예약 ID로는 삭제할 수 없고 그 예약은 그대로 남는다")
		void other_member_cannot_cancel() {
			// given
			reservationService.reserve(reservation("RV1", 11L), TTL);

			// when
			reservationService.cancel("RV1", "202507300002");

			// then - 요청자의 인덱스에 없으므로 성공처럼 끝나지만 예약은 그대로다
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("R:RV1");
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isTrue();
			assertThat(memberIndexValue("RV1")).isEqualTo(String.valueOf(SCHEDULE_ID));
		}

		@Test
		@DisplayName("인덱스와 본문의 회원번호가 어긋나면 RESERVATION_ACCESS_DENIED 예외가 발생하고 점유는 남는다")
		void owner_mismatch() {
			// given - 요청자 인덱스에는 있으나 본문은 다른 회원 소유
			Reservation foreign = ReservationFixture.builder().withReservationId("RV1")
				.withMemberNo("202507300002").withTrainScheduleId(SCHEDULE_ID).withSeatIds(CAR_ID, 11L).build();
			reservationTestHelper.saveBodyOnly(foreign);
			reservationTestHelper.saveIndexOnly(reservation("RV1", 11L));
			seatOccupancyTestHelper.markReserved(SCHEDULE_ID, CAR_ID, 11L, 0, 1, "RV1");

			// when

			// then
			assertThatThrownBy(() -> reservationService.cancel("RV1", MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_ACCESS_DENIED);
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("R:RV1");
		}

		@Test
		@DisplayName("점유 해제 스크립트가 실패하면 SEAT_OCCUPANCY_RELEASE_FAILED 예외가 발생하고 회원 인덱스는 남는다")
		void release_failure_keeps_index() {
			// given
			reservationService.reserve(reservation("RV1", 11L), TTL);
			doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_RELEASE_FAILED))
				.when(seatOccupancyRepository).release(any());

			// when

			// then
			assertThatThrownBy(() -> reservationService.cancel("RV1", MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_RELEASE_FAILED);
			assertThat(memberIndexValue("RV1")).isEqualTo(String.valueOf(SCHEDULE_ID));
		}
	}
}
