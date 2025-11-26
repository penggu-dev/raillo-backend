package com.sudo.raillo.booking.infrastructure;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.status.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Long> {
	Page<Booking> findAllByExpiresAtBeforeAndBookingStatus(
		LocalDateTime expiresAtBefore,
		BookingStatus bookingStatus,
		Pageable pageable
	);

	void deleteAllByMemberId(Long memberId);
}
