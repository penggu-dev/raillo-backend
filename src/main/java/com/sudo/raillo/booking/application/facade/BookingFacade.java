package com.sudo.raillo.booking.application.facade;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.member.domain.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingFacade {

	private final BookingService bookingService;
	private final TicketService ticketService;

	public void cancelBooking(Booking booking) {
		Long bookingId = booking.getId();
		bookingService.deleteSeatBookingByBookingId(bookingId);
		ticketService.deleteTicketByBookingId(bookingId);
	}

	public void deleteBookingsByMember(Member member) {
		bookingService.deleteAllByMemberId(member.getId());
	}
}
