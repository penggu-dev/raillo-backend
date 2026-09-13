package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.projection.TicketProjection;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.dto.response.TicketDetail;

@Component
public class BookingMapper {

	public List<BookingResponse> convertToBookingResponse(List<BookingInfo> bookingInfos) {
		return bookingInfos.stream()
			.map(this::convertToBookingResponse)
			.toList();
	}

	public BookingResponse convertToBookingResponse(BookingInfo bookingInfo) {
		return new BookingResponse(
			bookingInfo.bookingId(),
			bookingInfo.bookingCode(),
			String.format("%03d", bookingInfo.trainNumber()),
			bookingInfo.trainName(),
			bookingInfo.departureStationName(),
			bookingInfo.arrivalStationName(),
			bookingInfo.departureTime(),
			bookingInfo.arrivalTime(),
			bookingInfo.operationDate(),
			convertToTicketDetail(bookingInfo.tickets())
		);
	}

	private List<TicketDetail> convertToTicketDetail(List<TicketProjection> projection) {
		return projection.stream()
			.map(p -> new TicketDetail(
				p.getTicketId(),
				p.getTicketNumber(),
				p.getTicketStatus(),
				p.getPassengerType(),
				p.getCarNumber(),
				p.getCarType(),
				p.getSeatNumber()
			))
			.toList();
	}
}
