package com.sudo.raillo.booking.application.mapper;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.SeatInfo;
import com.sudo.raillo.booking.application.dto.StopInfo;
import com.sudo.raillo.booking.application.dto.TrainScheduleInfo;
import com.sudo.raillo.booking.application.dto.response.PendingBookingDetail;
import com.sudo.raillo.booking.application.dto.response.PendingSeatBookingDetail;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;

@Component
public class PendingBookingMapper {

	public PendingBookingDetail convertToPendingBookingDetail(
		PendingBooking pendingBooking,
		Map<Long, TrainScheduleInfo> trainScheduleMap,
		Map<Long, StopInfo> scheduleStopMap,
		Map<Long, SeatInfo> seatMap
	) {
		TrainScheduleInfo trainScheduleInfo = trainScheduleMap.get(pendingBooking.getTrainScheduleId());
		StopInfo departureStopInfo = scheduleStopMap.get(pendingBooking.getDepartureStopId());
		StopInfo arrivalStopInfo = scheduleStopMap.get(pendingBooking.getArrivalStopId());

		List<PendingSeatBookingDetail> seatDetails = convertToPendingSeatBookingDetails(
			pendingBooking.getPendingSeatBookings(),
			seatMap
		);

		return PendingBookingDetail.of(
			pendingBooking.getId(),
			trainScheduleInfo.trainNumber(),
			trainScheduleInfo.trainName(),
			departureStopInfo.stationName(),
			arrivalStopInfo.stationName(),
			departureStopInfo.departureTime(),
			arrivalStopInfo.arrivalTime(),
			trainScheduleInfo.operationDate(),
			seatDetails
		);
	}

	private List<PendingSeatBookingDetail> convertToPendingSeatBookingDetails(
		List<PendingSeatBooking> pendingSeatBookings,
		Map<Long, SeatInfo> seatMap
	) {
		return pendingSeatBookings.stream()
			.map(pendingSeatBooking -> {
				SeatInfo seatInfo = seatMap.get(pendingSeatBooking.seatId());
				return PendingSeatBookingDetail.of(
					pendingSeatBooking.seatId(),
					pendingSeatBooking.passengerType(),
					seatInfo.carNumber(),
					seatInfo.carType(),
					seatInfo.seatNumber()
				);
			})
			.toList();
	}
}
