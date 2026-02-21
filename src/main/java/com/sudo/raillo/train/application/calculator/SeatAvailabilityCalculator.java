package com.sudo.raillo.train.application.calculator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.application.dto.SectionSeatStatus;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.extern.slf4j.Slf4j;

/**
 * 좌석 가용성 계산
 * 책임: 좌석 잔여석 및 예약 가능 여부 계산 로직
 */
@Slf4j
@Component
public class SeatAvailabilityCalculator {

	/**
	 * 구간별 좌석 상태 계산 (전체 좌석 - SeatBooking - Hold = 잔여석)
	 */
	public SectionSeatStatus calculateSectionSeatStatus(
		List<SeatBookingInfo> overlappingBookings,
		Map<CarType, Integer> totalSeats,
		Map<CarType, Integer> holdSeatsCountByCarType,
		int requestedPassengerCount
	) {
		Map<CarType, Long> seatBooking = overlappingBookings.stream()
			.collect(Collectors.groupingBy(SeatBookingInfo::carType, Collectors.counting()));

		int standardRemaining = calculateRemaining(CarType.STANDARD, totalSeats, seatBooking, holdSeatsCountByCarType);
		int firstClassRemaining = calculateRemaining(CarType.FIRST_CLASS, totalSeats, seatBooking, holdSeatsCountByCarType);

		return new SectionSeatStatus(
			standardRemaining,
			totalSeats.getOrDefault(CarType.STANDARD, 0),
			firstClassRemaining,
			totalSeats.getOrDefault(CarType.FIRST_CLASS, 0),
			standardRemaining >= requestedPassengerCount,
			firstClassRemaining >= requestedPassengerCount
		);
	}

	private int calculateRemaining(
		CarType carType,
		Map<CarType, Integer> totalSeats,
		Map<CarType, Long> occupySeatBooking,
		Map<CarType, Integer> holdSeatsCountByCarType
	) {
		int total = totalSeats.getOrDefault(carType, 0);
		int seatBooking = occupySeatBooking.getOrDefault(carType, 0L).intValue();
		int holdCount = holdSeatsCountByCarType.getOrDefault(carType, 0);
		return Math.max(0, total - seatBooking - holdCount);
	}
}
