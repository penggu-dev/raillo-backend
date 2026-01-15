package com.sudo.raillo.booking.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	void deleteAllByMemberId(Long memberId);

	boolean existsByOrderId(Long orderId);
}
