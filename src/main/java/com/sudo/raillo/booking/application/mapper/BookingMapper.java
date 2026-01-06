package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.projection.SeatBookingProjection;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.dto.response.SeatBookingDetail;

@Component
public class BookingMapper {

	public List<BookingDetail> convertToBookingDetail(List<BookingInfo> bookingInfos) {
		return bookingInfos.stream()
			.map(this::convertToBookingDetail)
			.toList();
	}

	public BookingDetail convertToBookingDetail(BookingInfo bookingInfo) {
		return BookingDetail.of(
			bookingInfo.bookingId(),
			bookingInfo.bookingCode(),
			String.format("%03d", bookingInfo.trainNumber()),
			bookingInfo.trainName(),
			bookingInfo.departureStationName(),
			bookingInfo.arrivalStationName(),
			bookingInfo.departureTime(),
			bookingInfo.arrivalTime(),
			bookingInfo.operationDate(),
			convertToSeatBookingDetail(bookingInfo.seats())
		);
	}

	private List<SeatBookingDetail> convertToSeatBookingDetail(List<SeatBookingProjection> projection) {
		return projection.stream()
			.map(p -> SeatBookingDetail.of(
				p.getSeatBookingId(),
				p.getPassengerType(),
				p.getCarNumber(),
				p.getCarType(),
				p.getSeatNumber()
			))
			.toList();
	}


}
