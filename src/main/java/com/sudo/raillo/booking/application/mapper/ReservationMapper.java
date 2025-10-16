package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.projection.SeatReservationProjection;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.dto.response.SeatReservationDetail;

@Component
public class ReservationMapper {

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
