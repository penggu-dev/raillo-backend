package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	void deleteAllByMemberId(Long memberId);
}
