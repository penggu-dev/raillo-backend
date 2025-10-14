package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.booking.domain.CartReservation;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.member.domain.Member;

public interface CartReservationRepository extends JpaRepository<CartReservation, Long> {

	boolean existsByReservation(Reservation reservation);

	@Query("SELECT cr.reservation.id FROM CartReservation cr WHERE cr.member = :member")
	List<Long> findReservationIdsByMember(Member member);
}
