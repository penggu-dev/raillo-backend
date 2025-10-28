package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.projection.SeatReservationProjection;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.dto.response.SeatReservationDetail;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationMapper {

	private final ObjectMapper objectMapper;

	public List<ReservationDetail> convertToReservationDetail(List<ReservationInfo> reservationInfos) {
		return reservationInfos.stream()
			.map(this::convertToReservationDetail)
			.toList();
	}

	public ReservationDetail convertToReservationDetail(ReservationInfo reservationInfo) {
		return ReservationDetail.of(
			reservationInfo.reservationId(),
			reservationInfo.reservationCode(),
			String.format("%03d", reservationInfo.trainNumber()),
			reservationInfo.trainName(),
			reservationInfo.departureStationName(),
			reservationInfo.arrivalStationName(),
			reservationInfo.departureTime(),
			reservationInfo.arrivalTime(),
			reservationInfo.operationDate(),
			reservationInfo.expiresAt(),
			reservationInfo.fare(),
			convertToSeatReservationDetail(reservationInfo.seats())
		);
	}

	public String convertPassengersToJson(ReservationCreateRequest request) {
		try {
			return objectMapper.writeValueAsString(request.passengers());
		} catch (JsonProcessingException e) {
			log.error("승객 정보 JSON 변환 실패: {}", request.passengers(), e);
			throw new BusinessException(BookingError.RESERVATION_CREATE_FAILED);
		}
	}

	private List<SeatReservationDetail> convertToSeatReservationDetail(List<SeatReservationProjection> projection) {
		return projection.stream()
			.map(p -> SeatReservationDetail.of(
				p.getSeatReservationId(),
				p.getPassengerType(),
				p.getCarNumber(),
				p.getCarType(),
				p.getSeatNumber()
			))
			.toList();
	}


}
