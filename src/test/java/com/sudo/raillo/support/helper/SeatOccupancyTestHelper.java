package com.sudo.raillo.support.helper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.SeatOccupancyService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

import lombok.RequiredArgsConstructor;

/**
 * 예약(HELD) 좌석 점유를 만드는 테스트 헬퍼
 *
 * <p>확정 예매(CONFIRMED) 점유는 {@link BookingTestHelper}가 예매 생성 시 함께 만든다.</p>
 */
@Component
@RequiredArgsConstructor
@Transactional
public class SeatOccupancyTestHelper {

	private static final String DEFAULT_MEMBER_NO = "202601010001";
	private static final BigDecimal DEFAULT_TOTAL_FARE = BigDecimal.valueOf(50000);

	private final ReservationRepository reservationRepository;
	private final SeatOccupancyService seatOccupancyService;

	/**
	 * 좌석을 10분간 점유한다.
	 */
	public Reservation hold(
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Seat> seats
	) {
		return hold(trainSchedule, departureStop, arrivalStop, seats, LocalDateTime.now().plusMinutes(10));
	}

	/**
	 * 만료 시각을 지정해 좌석을 점유한다. 과거 시각을 주면 만료된 점유가 된다.
	 */
	public Reservation hold(
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Seat> seats,
		LocalDateTime expiresAt
	) {
		Reservation reservation = reservationRepository.save(Reservation.create(
			generateReservationCode(),
			DEFAULT_MEMBER_NO,
			trainSchedule,
			departureStop,
			arrivalStop,
			DEFAULT_TOTAL_FARE,
			expiresAt
		));

		seatOccupancyService.hold(reservation, seats);
		return reservation;
	}

	private String generateReservationCode() {
		return "PB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}
}
