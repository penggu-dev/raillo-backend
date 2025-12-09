package com.sudo.raillo.booking.application.facade;

import com.sudo.raillo.booking.application.dto.request.BookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingCreateResponse;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.SeatBookingService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.type.CarType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingFacade {

	private final BookingService bookingService;
	private final SeatBookingService seatBookingService;
	private final TicketService ticketService;
	private final BookingValidator bookingValidator;

	public BookingCreateResponse createBooking(BookingCreateRequest request, String memberNo) {
		// TODO: 요청 파라미터를 여기서 모두 검증할지, 각 서비스에서 검증할지 결정 필요
		CarType carType = bookingService.findCarType(request.seatIds());

		Booking booking = bookingService.createBooking(request, memberNo);
		bookingValidator.validateStopSequence(booking);

		// 승객 정보, 좌석 정보 정렬 (승객 정보는 PassengerType에 정의한 순서대로, 좌석 정보는 오름차순)
		List<PassengerSummary> passengers = new ArrayList<>(request.passengers());
		passengers.sort(Comparator.comparingInt(ps -> ps.getPassengerType().ordinal()));
		List<Long> seatIds = new ArrayList<>(request.seatIds());
		seatIds.sort(Comparator.naturalOrder());

		bookingValidator.validatePassengerSeatCount(passengers, seatIds);
		List<Long> seatBookingIds = seatBookingService.createSeatBookings(booking, passengers, seatIds);

		return new BookingCreateResponse(booking.getId(), seatBookingIds);
	}

	public void cancelBooking(Booking booking) {
		Long bookingId = booking.getId();
		seatBookingService.deleteSeatBookingByBookingId(bookingId);
		ticketService.deleteTicketByBookingId(bookingId);
	}

	public void deleteBookingsByMember(Member member) {
		bookingService.deleteAllByMemberId(member.getId());
	}
}
