package com.sudo.raillo.booking.application.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 옛 예약(PendingBooking) 조회·삭제. 결제 도메인이 아직 참조하므로 남겨 둔다.
 *
 * <p>TODO(결제 전환 PR): 결제가 {@link ReservationService}를 쓰도록 바꾼 뒤 이 클래스와 Seat Hold 스택을 제거한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingBookingService {

	private final BookingRedisRepository bookingRedisRepository;
	private final BookingValidator bookingValidator;

	/**
	 * 여러 PendingBooking 한 번에 조회 및 검증. 모든 예약이 Redis에 존재하고 소유자가 일치해야 한다.
	 */
	@Transactional(readOnly = true)
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds, String memberNo) {
		Map<String, PendingBooking> bookingsById = bookingRedisRepository.getPendingBookingsAsMap(pendingBookingIds);

		bookingValidator.validateAllPendingBookingsExist(pendingBookingIds, bookingsById);

		List<PendingBooking> pendingBookings = pendingBookingIds.stream()
			.map(bookingsById::get)
			.toList();

		bookingValidator.validatePendingBookingOwner(pendingBookings, memberNo);

		return pendingBookings;
	}

	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		bookingRedisRepository.deletePendingBookings(pendingBookingIds, memberNo);
	}

	/**
	 * PendingBooking TTL 계산. 출발까지 남은 시간과 기본 TTL 중 짧은 값을 반환한다.
	 */
	public Duration calculatePendingBookingTtl(LocalDateTime departureDateTime, LocalDateTime now) {
		Duration remainingUntilDeparture = Duration.between(now, departureDateTime);
		Duration defaultPendingBookingTtl = bookingRedisRepository.getPendingBookingExpireTime();

		return remainingUntilDeparture.compareTo(defaultPendingBookingTtl) < 0
			? remainingUntilDeparture
			: defaultPendingBookingTtl;
	}
}
