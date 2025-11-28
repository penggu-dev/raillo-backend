package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Cart;
import com.sudo.raillo.member.domain.Member;

public interface CartRepository extends JpaRepository<Cart, Long> {

	boolean existsByBooking(Booking booking);

	@Query("SELECT cr.booking.id FROM Cart cr WHERE cr.member = :member")
	List<Long> findBookingIdsByMember(Member member);
}
