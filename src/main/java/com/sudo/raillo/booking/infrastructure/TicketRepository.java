package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

	void deleteAllByReservationId(Long reservationId);
}
