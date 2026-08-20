package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingBookingService - calculatePendingBookingTtl 테스트")
class PendingBookingTtlTest {

	@InjectMocks
	private PendingBookingService pendingBookingService;

	@Mock
	private BookingRedisRepository bookingRedisRepository;

	@Test
	@DisplayName("출발까지 잔여 시간이 기본 TTL보다 짧으면 잔여 시간을 반환한다")
	void remainingTime_lessThan_defaultTtl() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 8, 55);
		LocalDateTime departureDateTime = LocalDateTime.of(2026, 1, 1, 9, 0);
		when(bookingRedisRepository.getPendingBookingExpireTime()).thenReturn(Duration.ofMinutes(10));

		// when - 출발까지 5분 남음 < 기본 TTL 10분
		Duration result = pendingBookingService.calculatePendingBookingTtl(departureDateTime, now);

		// then
		assertThat(result).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	@DisplayName("출발까지 잔여 시간이 기본 TTL보다 길면 기본 TTL을 반환한다")
	void remainingTime_greaterThan_defaultTtl() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 7, 0);
		LocalDateTime departureDateTime = LocalDateTime.of(2026, 1, 1, 9, 0);
		Duration defaultTtl = Duration.ofMinutes(10);
		when(bookingRedisRepository.getPendingBookingExpireTime()).thenReturn(defaultTtl);

		// when - 출발까지 120분 남음 > 기본 TTL 10분
		Duration result = pendingBookingService.calculatePendingBookingTtl(departureDateTime, now);

		// then
		assertThat(result).isEqualTo(defaultTtl);
	}

	@Test
	@DisplayName("출발까지 잔여 시간이 기본 TTL과 같으면 기본 TTL을 반환한다")
	void remainingTime_equalTo_defaultTtl() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 8, 50);
		LocalDateTime departureDateTime = LocalDateTime.of(2026, 1, 1, 9, 0);
		Duration defaultTtl = Duration.ofMinutes(10);
		when(bookingRedisRepository.getPendingBookingExpireTime()).thenReturn(defaultTtl);

		// when - 출발까지 10분 남음 = 기본 TTL
		Duration result = pendingBookingService.calculatePendingBookingTtl(departureDateTime, now);

		// then
		assertThat(result).isEqualTo(defaultTtl);
	}
}
