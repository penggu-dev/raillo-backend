package com.sudo.raillo.booking.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	void deleteAllByMemberId(Long memberId);
}
