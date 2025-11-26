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
	 * 배치로 조회된 데이터를 사용해 구간별 좌석 상태 계산
	 */
	public SectionSeatStatus calculateSectionSeatStatus(
		List<SeatBookingInfo> overlappingBookings,
		Map<CarType, Integer> totalSeats,
		int requestedPassengerCount) {

		// 1. 좌석 타입별 잔여 좌석 계산
		SeatCalculationResult seatResult = calculateRemainingSeats(
			totalSeats, overlappingBookings);

		// 2. 예약 가능 여부 판단
		boolean canReserveStandard = seatResult.standardRemaining() >= requestedPassengerCount;
		boolean canReserveFirstClass = seatResult.firstClassRemaining() >= requestedPassengerCount;

		return SectionSeatStatus.of(
			seatResult.standardRemaining(),
			totalSeats.getOrDefault(CarType.STANDARD, 0),
			seatResult.firstClassRemaining(),
			totalSeats.getOrDefault(CarType.FIRST_CLASS, 0),
			canReserveStandard,
			canReserveFirstClass
		);
	}

	/**
	 * 좌석 타입별 잔여 좌석 계산
	 */
	public SeatCalculationResult calculateRemainingSeats(Map<CarType, Integer> totalSeats,
		List<SeatBookingInfo> overlappingBookings) {
		// 예약된 좌석 수 계산
		Map<CarType, Long> reservedSeats = overlappingBookings.stream()
			.collect(Collectors.groupingBy(SeatBookingInfo::carType, Collectors.counting()));

		int standardTotal = totalSeats.getOrDefault(CarType.STANDARD, 0);
		int firstClassTotal = totalSeats.getOrDefault(CarType.FIRST_CLASS, 0);
		int standardReserved = reservedSeats.getOrDefault(CarType.STANDARD, 0L).intValue();
		int firstClassReserved = reservedSeats.getOrDefault(CarType.FIRST_CLASS, 0L).intValue();

		return new SeatCalculationResult(
			Math.max(0, standardTotal - standardReserved), standardTotal,
			Math.max(0, firstClassTotal - firstClassReserved), firstClassTotal
		);
	}

	public record SeatCalculationResult(
		int standardRemaining,
		int standardTotal,
		int firstClassRemaining,
		int firstClassTotal
	) {}
}
