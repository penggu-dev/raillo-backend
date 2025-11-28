package com.sudo.raillo.booking.application.mapper;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.SeatPassengerPair;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.Seat;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SeatPassengerMapper {

	public List<SeatPassengerPair> mapSeatsToPassengers(
		List<PassengerSummary> passengers,
		List<Seat> seats
	) {
		// 좌석 차례대로 승객 할당
		int idx = 0;
		List<SeatPassengerPair> pairs = new ArrayList<>();
		for (PassengerSummary passenger : passengers) {
			PassengerType passengerType = passenger.getPassengerType();
			int passengerCnt = passenger.getCount();
			for (int i = 0; i < passengerCnt && idx < seats.size(); i++, idx++) {
				pairs.add(new SeatPassengerPair(seats.get(idx), passengerType));
			}
		}

		return pairs;
	}
}
