package com.sudo.raillo.booking.application.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.ReservationDraft;
import com.sudo.raillo.booking.application.dto.response.ReservationResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationResponse.SeatResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationResponse.StopResponse;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.ReservationStop;
import com.sudo.raillo.train.application.dto.ReservationTrainContext;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;

@Component
public class ReservationMapper {

	public Reservation toReservation(ReservationDraft draft) {
		ReservationTrainContext train = draft.train();
		ScheduleInfoCacheValue schedule = train.schedule();

		return Reservation.create(
			draft.reservationId(),
			draft.memberNo(),
			schedule.trainScheduleId(),
			schedule.trainNumber(),
			schedule.trainName(),
			schedule.operationDate(),
			toDepartureStop(train.departureStop()),
			toArrivalStop(train.arrivalStop()),
			train.departureAt(),
			draft.carType(),
			toSeats(draft),
			draft.createdAt(),
			draft.ttl()
		);
	}

	public ReservationResponse toResponse(Reservation reservation) {
		return new ReservationResponse(
			reservation.reservationId(),
			reservation.trainNumber(),
			reservation.trainName(),
			reservation.operationDate(),
			toStopResponse(reservation.departure()),
			toStopResponse(reservation.arrival()),
			reservation.departureAt(),
			reservation.carType(),
			reservation.seats().stream()
				.map(seat -> new SeatResponse(seat.carNumber(), seat.seatLabel(), seat.passengerType(), seat.fare()))
				.toList(),
			reservation.totalFare(),
			reservation.expiresAt()
		);
	}

	private static StopResponse toStopResponse(ReservationStop stop) {
		return new StopResponse(stop.stationName(), stop.time());
	}

	/** 출발 정차역은 출발 시각을 담는다. */
	private static ReservationStop toDepartureStop(ScheduleStopCacheValue stop) {
		return new ReservationStop(stop.stopId(), stop.stopOrder(), stop.stationId(), stop.stationName(),
			stop.departureTime());
	}

	/** 도착 정차역은 도착 시각을 담는다. */
	private static ReservationStop toArrivalStop(ScheduleStopCacheValue stop) {
		return new ReservationStop(stop.stopId(), stop.stopOrder(), stop.stationId(), stop.stationName(),
			stop.arrivalTime());
	}

	private static List<ReservationSeat> toSeats(ReservationDraft draft) {
		List<ReservationSeat> seats = new ArrayList<>();
		int index = 0;
		for (Map.Entry<Long, SeatCacheValue> entry : draft.train().seatsById().entrySet()) {
			SeatCacheValue seat = entry.getValue();
			seats.add(new ReservationSeat(
				entry.getKey(),
				seat.trainCarId(),
				seat.carNumber(),
				seat.seatRow() + seat.seatColumn(),
				draft.passengerTypes().get(index),
				draft.fares().get(index)
			));
			index++;
		}
		return seats;
	}
}
