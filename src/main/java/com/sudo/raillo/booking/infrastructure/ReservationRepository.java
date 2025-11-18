package com.sudo.raillo.booking.infrastructure;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
	Page<Reservation> findAllByExpiresAtBeforeAndReservationStatus(
		LocalDateTime expiresAtBefore,
		ReservationStatus reservationStatus,
		Pageable pageable
	);

	void deleteAllByMemberId(Long memberId);
}
