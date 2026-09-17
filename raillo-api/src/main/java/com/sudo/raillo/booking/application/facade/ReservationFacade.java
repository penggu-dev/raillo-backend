package com.sudo.raillo.booking.application.facade;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.application.dto.ReservationDraft;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.util.ReservationIdGenerator;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.application.dto.ReservationTrainContext;
import com.sudo.raillo.train.application.service.TrainCacheQueryService;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 생성 조율. 기준정보 조회 → 검증 → 운임·TTL → 원자적 예약. DB를 보지 않으므로 트랜잭션을 열지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationFacade {

	private final TrainCacheQueryService trainCacheQueryService;
	private final ReservationValidator reservationValidator;
	private final FareCalculator fareCalculator;
	private final ReservationService reservationService;
	private final ReservationMapper reservationMapper;
	private final ReservationIdGenerator reservationIdGenerator;

	public ReservationCreateResponse createReservation(ReservationCreateRequest request, String memberNo) {
		// 1. 요청 자체 검증
		reservationValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());
		reservationValidator.validateDistinctSeats(request.seatIds());
		reservationValidator.validateDifferentStations(request.departureStationId(), request.arrivalStationId());

		// 2. 기준정보 조회 (Redis 파이프라인 1회)
		ReservationTrainContext train = trainCacheQueryService.getReservationContext(
			request.trainScheduleId(), request.departureStationId(), request.arrivalStationId(), request.seatIds());

		// 3. 운행·구간·좌석 검증
		LocalDateTime now = LocalDateTime.now();
		reservationValidator.validateOperating(train.schedule());
		reservationValidator.validateStopSequence(train.departureStop(), train.arrivalStop());
		reservationValidator.validateBookingOpen(train.departureAt(), now);
		CarType carType = reservationValidator.validateSingleCarType(train.seatsById().values());

		// 4. 운임·TTL
		List<BigDecimal> fares = fareCalculator.calculateFares(train.fare(), carType, request.passengerTypes());
		Duration ttl = reservationService.calculateTtl(train.departureAt(), now);

		// 5. 예약 조립과 원자적 점유
		Reservation reservation = reservationMapper.toReservation(new ReservationDraft(
			reservationIdGenerator.generate(), memberNo, train, carType, request.passengerTypes(), fares, now, ttl));
		reservationService.reserve(reservation, ttl);

		return new ReservationCreateResponse(reservation.reservationId(), reservation.expiresAt());
	}
}
