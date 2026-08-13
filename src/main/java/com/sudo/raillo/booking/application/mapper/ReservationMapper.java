package com.sudo.raillo.booking.application.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.response.ReservationDetailResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationSeatDetail;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

/**
 * 예약 응답 매퍼
 *
 * <p>Reservation이 연관 엔티티를 직접 들고 있으므로, Redis 시절처럼 스케줄·정차역·좌석을
 * 따로 조회해 Map으로 맞붙일 필요가 없다.</p>
 */
@Component
public class ReservationMapper {

	public ReservationDetailResponse convertToReservationDetail(
		Reservation reservation,
		List<ReservationSeat> reservationSeats
	) {
		TrainSchedule trainSchedule = reservation.getTrainSchedule();
		ScheduleStop departureStop = reservation.getDepartureStop();
		ScheduleStop arrivalStop = reservation.getArrivalStop();

		return new ReservationDetailResponse(
			reservation.getReservationCode(),
			String.format("%03d", trainSchedule.getTrain().getTrainNumber()),
			trainSchedule.getTrain().getTrainName(),
			departureStop.getStation().getStationName(),
			arrivalStop.getStation().getStationName(),
			departureStop.getDepartureTime(),
			arrivalStop.getArrivalTime(),
			trainSchedule.getOperationDate(),
			reservation.getTotalFare(),
			convertToSeatDetails(reservationSeats)
		);
	}

	private List<ReservationSeatDetail> convertToSeatDetails(List<ReservationSeat> reservationSeats) {
		return reservationSeats.stream()
			.map(reservationSeat -> {
				Seat seat = reservationSeat.getSeat();
				return new ReservationSeatDetail(
					seat.getId(),
					reservationSeat.getPassengerType(),
					seat.getTrainCar().getCarNumber(),
					seat.getTrainCar().getCarType(),
					String.valueOf(seat.getSeatRow()).concat(seat.getSeatColumn())
				);
			})
			.toList();
	}
}
