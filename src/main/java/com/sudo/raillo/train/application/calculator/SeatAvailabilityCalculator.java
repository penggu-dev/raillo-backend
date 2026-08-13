package com.sudo.raillo.train.application.calculator;

import java.util.Map;

import org.springframework.stereotype.Component;

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
	 * 구간별 좌석 상태 계산 (전체 좌석 - 점유 좌석 = 잔여석)
	 *
	 * <p>점유 좌석은 예약(HELD)과 확정 예매(CONFIRMED)를 모두 포함한 단일 집계값이다.</p>
	 *
	 * @param occupiedSeats CarType별 점유 좌석 수
	 * @param totalSeats CarType별 전체 좌석 수
	 */
	public SectionSeatStatus calculateSectionSeatStatus(
		Map<CarType, Integer> occupiedSeats,
		Map<CarType, Integer> totalSeats,
		int requestedPassengerCount
	) {
		int standardRemaining = calculateRemaining(CarType.STANDARD, totalSeats, occupiedSeats);
		int firstClassRemaining = calculateRemaining(CarType.FIRST_CLASS, totalSeats, occupiedSeats);

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
		Map<CarType, Integer> occupiedSeats
	) {
		int total = totalSeats.getOrDefault(carType, 0);
		int occupied = occupiedSeats.getOrDefault(carType, 0);
		return Math.max(0, total - occupied);
	}
}
