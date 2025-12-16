package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.projection.SeatBookingProjection;
import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.dto.response.SeatBookingDetail;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingMapper {

	private final ObjectMapper objectMapper;

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

	public String convertPassengersToJson(PendingBookingCreateRequest request) {
		try {
			return objectMapper.writeValueAsString(request.passengers());
		} catch (JsonProcessingException e) {
			log.error("승객 정보 JSON 변환 실패: {}", request.passengers(), e);
			throw new BusinessException(BookingError.BOOKING_CREATE_FAILED);
		}
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
