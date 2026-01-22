package com.sudo.raillo.booking.application.facade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PendingBookingFacade {

	private final PendingBookingService pendingBookingService;
	private final SeatHoldService seatHoldService;
	private final TrainSeatQueryService trainSeatQueryService;
	private final FareCalculator fareCalculator;
	private final BookingValidator bookingValidator;

	/**
	 * 예약 생성
	 * 조회 → 검증 → 운임 계산 → 좌석 Hold → PendingBooking 저장
	 */
	public PendingBookingCreateResponse createPendingBooking(PendingBookingCreateRequest request, String memberNo) {
		// 1. 조회
		TrainSchedule trainSchedule = pendingBookingService.getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = pendingBookingService.getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = pendingBookingService.getStopStation(trainSchedule, request.arrivalStationId());

		// 2. 검증
		bookingValidator.validateTrainOperating(trainSchedule);
		bookingValidator.validateSameSchedule(departureStop, arrivalStop);
		bookingValidator.validateStopSequence(departureStop, arrivalStop);
		bookingValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());

		List<CarType> carTypes = trainSeatQueryService.getCarTypes(request.seatIds());
		CarType carType = bookingValidator.validateSeatIdsAndGetSingleCarType(carTypes);

		// 3. 운임 계산
		BigDecimal totalFare = fareCalculator.calculateTotalFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengerTypes(),
			carType
		);

		// 4. 좌석 Hold & 5. 저장 (Hold 이후 실패 시 보상 로직)
		String pendingBookingId = UUID.randomUUID().toString();
		seatHoldService.holdSeats(
			pendingBookingId,
			request.trainScheduleId(),
			departureStop.getId(),
			arrivalStop.getId(),
			request.seatIds()
		);

		try {
			PendingBooking pendingBooking = pendingBookingService.createPendingBooking(
				pendingBookingId,
				trainSchedule,
				departureStop,
				arrivalStop,
				request.passengerTypes(),
				request.seatIds(),
				memberNo,
				totalFare
			);

			return new PendingBookingCreateResponse(pendingBooking.getId());
		} catch (Exception e) {
			log.error("[PendingBooking 저장 실패 - Hold 롤백] pendingBookingId={}, error={}", pendingBookingId, e.getMessage());
			seatHoldService.releaseSeats(pendingBookingId, request.trainScheduleId(), request.seatIds());
			throw e;
		}
	}

	/**
	 * 예약 삭제
	 * 좌석 Hold 해제 → PendingBooking 삭제
	 */
	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		List<PendingBooking> pendingBookings = pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);

		pendingBookings.forEach(pb -> {
			try {
				seatHoldService.releaseSeats(
					pb.getId(),
					pb.getTrainScheduleId(),
					extractSeatIds(pb)
				);
			} catch (Exception e) {
				log.warn("[좌석 Hold 해제 실패] pendingBookingId={}, error={}", pb.getId(), e.getMessage());
			}
		});

		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);
	}

	private List<Long> extractSeatIds(PendingBooking pb) {
		return pb.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}
}
