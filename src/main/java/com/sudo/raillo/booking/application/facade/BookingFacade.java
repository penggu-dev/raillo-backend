package com.sudo.raillo.booking.application.facade;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.FareCalculationService;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.type.CarType;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingFacade {

	private final BookingService bookingService;
	private final PendingBookingService pendingBookingService;
	private final TicketService ticketService;
	private final FareCalculationService fareCalculationService;

	public PendingBookingCreateResponse createPendingBooking(PendingBookingCreateRequest request, String memberNo) {
		CarType carType = pendingBookingService.findCarType(request.seatIds());
		BigDecimal totalFare = fareCalculationService.calculateFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengerTypes(),
			carType
		);

		// TODO: 좌석락 로직 필요

		PendingBooking pendingBooking = pendingBookingService.createPendingBooking(request, memberNo, totalFare);

		return new PendingBookingCreateResponse(pendingBooking.getId());
	}

	public void cancelBooking(Booking booking) {
		Long bookingId = booking.getId();
		bookingService.deleteSeatBookingByBookingId(bookingId);
		ticketService.deleteTicketByBookingId(bookingId);
	}

	public void deleteBookingsByMember(Member member) {
		bookingService.deleteAllByMemberId(member.getId());
	}
}
