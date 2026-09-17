package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
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
		void reserveSuccess() {
			// given - 서울(0) → 부산(1), 좌석 2개
			Reservation reservation = reservation("RV1", 11L, 12L);

			// when
			reservationService.reserve(reservation, TTL);

			// then
			assertThat(memberIndexValue("RV1")).isEqualTo(String.valueOf(SCHEDULE_ID));
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 11L, 0)).isEqualTo("H:RV1");
			assertThat(seatOccupancyTestHelper.valueOf(SCHEDULE_ID, CAR_ID, 12L, 0)).isEqualTo("H:RV1");

			String json = stringRedisTemplate.opsForValue().get(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"));
			assertThat(redisJsonConverter.fromJson(json, Reservation.class)).isEqualTo(reservation);
			assertThat(stringRedisTemplate.getExpire(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"), TimeUnit.SECONDS))
				.isBetween(595L, 600L);
		}

		@Test
		@DisplayName("TTL이 정수 초가 아니면 올림해서 적용한다")
		void roundsTtlUp() {
			// given
			Reservation reservation = reservation("RV1", 11L);

			// when
			reservationService.reserve(reservation, Duration.ofMillis(1_200));

			// then - 1.2초 → 2초
			assertThat(stringRedisTemplate.getExpire(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"), TimeUnit.SECONDS))
				.isBetween(1L, 2L);
		}

		@Test
		@DisplayName("다른 예약이 점유한 구간이면 SEAT_CONFLICT_WITH_HOLD 예외가 발생하고 회원 인덱스는 남지 않는다")
		void conflictWithHold() {
			// given
			seatOccupancyTestHelper.markHeld(SCHEDULE_ID, CAR_ID, 11L, 0, 1, "OTHER");
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_HOLD);
			assertThat(memberIndexValue("RV1")).isNull();
			assertThat(stringRedisTemplate.hasKey(ReservationCacheKey.reservation(SCHEDULE_ID, "RV1"))).isFalse();
		}

		@Test
		@DisplayName("이미 판매된 구간이면 SEAT_CONFLICT_WITH_SOLD 예외가 발생하고 회원 인덱스는 남지 않는다")
		void conflictWithSold() {
			// given
			seatOccupancyTestHelper.markSold(SCHEDULE_ID, CAR_ID, 11L, 0, 1, "77");
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_SOLD);
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("점유 스크립트가 실패하면 예외가 전파되고 회원 인덱스는 되돌려진다")
		void scriptFailureRollsBackIndex() {
			// given
			doThrow(new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR))
				.when(seatOccupancyRepository).hold(any());
			Reservation reservation = reservation("RV1", 11L);

			// when

			// then
			assertThatThrownBy(() -> reservationService.reserve(reservation, TTL))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_HOLD_SCRIPT_ERROR);
			assertThat(memberIndexValue("RV1")).isNull();
		}

		@Test
		@DisplayName("회원 인덱스 등록이 실패하면 좌석 점유를 시도하지 않는다")
		void indexFailureSkipsHold() {
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
		void returnsInRequestOrder() {
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
		void missingIndex() {
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
		void missingBody() {
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
		void otherMember() {
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
		void emptyIds() {
			// given

			// when

			// then
			assertThatThrownBy(() -> reservationService.getReservations(List.of(), MEMBER_NO))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_IDS_REQUIRED);
		}
	}
}
