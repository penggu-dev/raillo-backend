package com.sudo.raillo.booking.application.dto.request;

import java.time.LocalDateTime;
import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

/**
 * 예약 생성 명령
 *
 * <p>{@code seats} 와 {@code passengerTypes} 는 같은 인덱스끼리 짝을 이룬다.
 * 따라서 좌석 목록은 반드시 요청 순서를 유지해야 한다.</p>
 *
 * @param reservationCode 예약 코드 (고객·외부 노출용 식별자)
 * @param expiresAt 예약 만료 시각 (Redis TTL 대체)
 */
public record ReservationCreateCommand(
	String reservationCode,
	String memberNo,
	TrainSchedule trainSchedule,
	ScheduleStop departureStop,
	ScheduleStop arrivalStop,
	List<Seat> seats,
	List<PassengerType> passengerTypes,
	LocalDateTime expiresAt
) {
}
