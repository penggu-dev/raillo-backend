package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.train.exception.TrainError;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService - calculateTtl")
class ReservationServiceTtlTest {

	private static final LocalDateTime DEPARTURE_AT = LocalDateTime.of(2026, 10, 20, 9, 0);
	private static final LocalDateTime CLOSE_AT = LocalDateTime.of(2026, 10, 20, 8, 55);

	@InjectMocks
	private ReservationService reservationService;

	@Mock
	private ReservationRedisRepository reservationRedisRepository;

	@Mock
	private SeatOccupancyRepository seatOccupancyRepository;

	@Mock
	private RedisJsonConverter redisJsonConverter;

	@Mock
	private ReservationValidator reservationValidator;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(reservationService, "defaultTtl", Duration.ofMinutes(10));
	}

	@Test
	@DisplayName("마감까지 남은 시간이 기본 TTL보다 길면 기본 TTL을 돌려준다")
	void default_ttl_when_far_from_close() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 10, 20, 7, 0);

		// when - 마감(08:55)까지 115분
		Duration ttl = reservationService.calculateTtl(DEPARTURE_AT, now);

		// then
		assertThat(ttl).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	@DisplayName("마감까지 남은 시간이 기본 TTL보다 짧으면 남은 시간을 돌려준다")
	void remaining_when_close_to_departure() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 10, 20, 8, 52);

		// when - 마감(08:55)까지 3분
		Duration ttl = reservationService.calculateTtl(DEPARTURE_AT, now);

		// then
		assertThat(ttl).isEqualTo(Duration.ofMinutes(3));
	}

	@Test
	@DisplayName("남은 시간이 1초 미만이면 1초로 올린다")
	void clamps_to_one_second() {
		// given - 마감 300ms 전
		LocalDateTime now = CLOSE_AT.minusNanos(300_000_000);

		// when
		Duration ttl = reservationService.calculateTtl(DEPARTURE_AT, now);

		// then
		assertThat(ttl).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	@DisplayName("출발 5분 전 마감 시각부터는 DEPARTURE_TIME_PASSED 예외가 발생해 예약할 수 없다")
	void rejects_from_close_time() {
		// given

		// when

		// then - 정확히 마감 시각, 마감 이후, 출발 이후 모두 거부
		assertThatThrownBy(() -> reservationService.calculateTtl(DEPARTURE_AT, CLOSE_AT))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
		assertThatThrownBy(() -> reservationService.calculateTtl(DEPARTURE_AT, CLOSE_AT.plusMinutes(2)))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
		assertThatThrownBy(() -> reservationService.calculateTtl(DEPARTURE_AT, DEPARTURE_AT.plusMinutes(1)))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
	}
}
