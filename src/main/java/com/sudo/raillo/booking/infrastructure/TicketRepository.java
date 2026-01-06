package com.sudo.raillo.booking.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Ticket;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

	void deleteAllByBookingId(Long bookingId);
}
