package com.sudo.raillo.booking.application.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.application.dto.ReservationTrainContext;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 검증과 운임 계산을 마친 예약 재료. {@code ReservationMapper}가 {@code Reservation}으로 조립한다.
 *
 * @param passengerTypes 요청 좌석 순서와 같다
 * @param fares 요청 좌석 순서와 같으며 승객 유형 할인이 적용된 값이다
 */
public record ReservationDraft(
	String reservationId,
	String memberNo,
	ReservationTrainContext train,
	CarType carType,
	List<PassengerType> passengerTypes,
	List<BigDecimal> fares,
	LocalDateTime createdAt,
	Duration ttl
) {
}
