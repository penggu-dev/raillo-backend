package com.sudo.raillo.booking.application.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.service.SeatOccupancyService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.util.ReservationCodeGenerator;
import com.sudo.raillo.train.application.service.TrainScheduleService;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationFacade {

	private final ReservationService reservationService;
	private final SeatOccupancyService seatOccupancyService;
	private final TrainSeatQueryService trainSeatQueryService;
	private final BookingValidator bookingValidator;
	private final ReservationCodeGenerator reservationCodeGenerator;
	private final TrainScheduleService trainScheduleService;

	/**
	 * 예약 생성 조회 → 검증 → 운임 계산 → 예약 저장 → 좌석 점유
	 *
	 * <p>좌석 충돌은 {@code uk_seat_occupancy_section} 유니크 제약이 판정한다.
	 * 전 과정이 하나의 트랜잭션이므로 어느 단계에서 실패하든 롤백으로 정리되며,
	 * 별도의 보상 로직이 필요 없다.</p>
	 */
	@Transactional
	public ReservationCreateResponse createReservation(ReservationCreateRequest request, String memberNo) {
		// 1. 조회
		TrainSchedule trainSchedule = trainScheduleService.getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = trainScheduleService.getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = trainScheduleService.getStopStation(trainSchedule, request.arrivalStationId());

		// 2. 검증
		bookingValidator.validateTrainOperating(trainSchedule);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime departureDateTime = trainSchedule.getDepartureDateTimeAt(departureStop);
		bookingValidator.validateDepartureTimeNotPassed(departureDateTime, now);
		bookingValidator.validateSameSchedule(departureStop, arrivalStop);
		bookingValidator.validateStopSequence(departureStop, arrivalStop);
		bookingValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());

		List<Seat> seats = trainSeatQueryService.getSeats(request.seatIds());
		bookingValidator.validateSeatIdsAndGetSingleCarType(trainSeatQueryService.getCarTypes(request.seatIds()));

		// 3. 좌석 충돌 사전 검증 (빠른 실패용 - 최종 판정은 유니크 제약)
		bookingValidator.validateSeatConflicts(trainSchedule.getId(), departureStop, arrivalStop, request.seatIds());

		// 4. 예약 저장 (운임은 좌석별로 계산되어 합산된다)
		Duration reservationTtl = reservationService.calculateReservationTtl(departureDateTime, now);
		Reservation reservation = reservationService.createHeld(new ReservationCreateCommand(
			reservationCodeGenerator.generate(),
			memberNo,
			trainSchedule,
			departureStop,
			arrivalStop,
			seats,
			request.passengerTypes(),
			now.plus(reservationTtl)
		));

		// 5. 좌석 점유 (유니크 제약 위반 시 SEAT_ALREADY_OCCUPIED)
		seatOccupancyService.hold(reservation, seats);

		return new ReservationCreateResponse(reservation.getReservationCode());
	}

	/**
	 * 예약 삭제 예약 해제 → 좌석 점유 행 물리 삭제
	 */
	@Transactional
	public void deleteReservations(List<String> reservationCodes, String memberNo) {
		List<Reservation> reservations = reservationService.getOwnedByCodes(reservationCodes, memberNo);

		List<Reservation> released = reservationService.release(reservations);
		seatOccupancyService.releaseByReservationIds(released.stream().map(Reservation::getId).toList());
	}
}
