package com.sudo.raillo.support.helper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateCommand;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.service.SeatOccupancyService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

import lombok.RequiredArgsConstructor;

/**
 * 예약(HELD)과 좌석 점유를 만드는 테스트 헬퍼
 *
 * <p>실제 예약 생성 경로와 동일하게 Reservation + ReservationSeat + SeatOccupancy를 모두 만든다.
 * 확정 예매(CONFIRMED) 점유는 {@link BookingTestHelper}가 예매 생성 시 함께 만든다.</p>
 */
@Component
@RequiredArgsConstructor
@Transactional
public class ReservationTestHelper {

	private static final String DEFAULT_MEMBER_NO = "202601010001";

	private final ReservationService reservationService;
	private final SeatOccupancyService seatOccupancyService;

	/**
	 * 좌석을 10분간 점유한다. (모든 승객 성인, 기본 회원번호)
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
		return hold(DEFAULT_MEMBER_NO, trainSchedule, departureStop, arrivalStop, seats,
			adultsFor(seats), expiresAt);
	}

	/**
	 * 회원·승객 유형·총 운임까지 지정해 예약을 만든다. (결제 흐름 테스트용)
	 */
	public Reservation hold(
		String memberNo,
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Seat> seats,
		List<PassengerType> passengerTypes
	) {
		return hold(memberNo, trainSchedule, departureStop, arrivalStop, seats, passengerTypes,
			LocalDateTime.now().plusMinutes(10));
	}

	public Reservation hold(
		String memberNo,
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Seat> seats,
		List<PassengerType> passengerTypes,
		LocalDateTime expiresAt
	) {
		Reservation reservation = reservationService.createHeld(new ReservationCreateCommand(
			generateReservationCode(),
			memberNo,
			trainSchedule,
			departureStop,
			arrivalStop,
			seats,
			passengerTypes,
			expiresAt
		));

		seatOccupancyService.hold(reservation, seats);
		return reservation;
	}

	private List<PassengerType> adultsFor(List<Seat> seats) {
		return Collections.nCopies(seats.size(), PassengerType.ADULT);
	}

	private String generateReservationCode() {
		return "PB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}
}
