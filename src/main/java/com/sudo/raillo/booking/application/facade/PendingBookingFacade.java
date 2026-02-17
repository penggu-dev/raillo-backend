package com.sudo.raillo.booking.application.facade;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.util.PendingBookingIdGenerator;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.application.service.TrainScheduleService;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.infrastructure.SeatRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingBookingFacade {

	private final PendingBookingService pendingBookingService;
	private final SeatHoldService seatHoldService;
	private final TrainSeatQueryService trainSeatQueryService;
	private final FareCalculator fareCalculator;
	private final BookingValidator bookingValidator;
	private final PendingBookingIdGenerator pendingBookingIdGenerator;
	private final TrainScheduleService trainScheduleService;
	private final SeatRepository seatRepository;

	/**
	 * 예약 생성
	 * 조회 → 검증 → 운임 계산 → 좌석 Hold -> DB 충돌 검증 →  → PendingBooking 저장
	 */
	public PendingBookingCreateResponse createPendingBooking(PendingBookingCreateRequest request, String memberNo) {
		// 1. 조회
		TrainSchedule trainSchedule = trainScheduleService.getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = trainScheduleService.getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = trainScheduleService.getStopStation(trainSchedule, request.arrivalStationId());

		// 2. 검증
		bookingValidator.validateTrainOperating(trainSchedule);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime departureDateTime = bookingValidator.calculateDepartureDateTime(trainSchedule, departureStop);
		bookingValidator.validateDepartureTimeNotPassed(departureDateTime, now);
		bookingValidator.validateSameSchedule(departureStop, arrivalStop);
		bookingValidator.validateStopSequence(departureStop, arrivalStop);
		bookingValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());

		Duration pendingBookingTtl = pendingBookingService.calculatePendingBookingTtl(departureDateTime, now);

		List<CarType> carTypes = trainSeatQueryService.getCarTypes(request.seatIds());
		CarType carType = bookingValidator.validateSeatIdsAndGetSingleCarType(carTypes);

		// 3. 운임 계산
		BigDecimal totalFare = fareCalculator.calculateTotalFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengerTypes(),
			carType
		);

		// 4. 좌석 Hold
		String pendingBookingId = pendingBookingIdGenerator.generate();
		Long trainCarId = getTrainCarId(request.seatIds());
		seatHoldService.holdSeats(
			pendingBookingId,
			request.trainScheduleId(),
			departureStop,
			arrivalStop,
			request.seatIds(),
			trainCarId,
			pendingBookingTtl
		);

		try {
			// 5. DB 충돌 검증
			bookingValidator.validateSeatConflicts(
				trainSchedule.getId(),
				departureStop,
				arrivalStop,
				request.seatIds()
			);

			// 6. PendingBooking 저장 (Hold 이후 실패 시 보상 로직)
			PendingBooking pendingBooking = pendingBookingService.createPendingBooking(
				pendingBookingId,
				trainSchedule,
				departureStop,
				arrivalStop,
				request.passengerTypes(),
				request.seatIds(),
				memberNo,
				totalFare,
				pendingBookingTtl
			);

			return new PendingBookingCreateResponse(pendingBooking.getId());
		} catch (Exception e) {
			log.error("[PendingBooking 저장 실패 - Hold 롤백] pendingBookingId={}, error={}", pendingBookingId, e.getMessage());
			seatHoldService.releaseSeats(
				pendingBookingId,
				request.trainScheduleId(),
				request.seatIds(),
				trainCarId,
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder()
			);
			throw e;
		}
	}

	/**
	 * 예약 삭제
	 * PendingBooking 삭제 (취소 확정) → 좌석 Hold 해제 (best-effort 정리)
	 */
	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		List<PendingBooking> pendingBookings = pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);

		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);

		pendingBookings.forEach(pendingBooking -> {
			try {
				List<Long> seatIds = extractSeatIds(pendingBooking);
				Long trainCarId = getTrainCarId(seatIds);
				TrainSchedule trainSchedule = trainScheduleService.getTrainSchedule(pendingBooking.getTrainScheduleId());
				ScheduleStop departureStop = trainScheduleService.getStopStation(trainSchedule, pendingBooking.getDepartureStopId());
				ScheduleStop arrivalStop = trainScheduleService.getStopStation(trainSchedule, pendingBooking.getArrivalStopId());

				seatHoldService.releaseSeats(
					pendingBooking.getId(),
					pendingBooking.getTrainScheduleId(),
					seatIds,
					trainCarId,
					departureStop.getStopOrder(),
					arrivalStop.getStopOrder()
				);
			} catch (Exception e) {
				log.warn("[좌석 Hold 해제 실패] pendingBookingId={}, error={}", pendingBooking.getId(), e.getMessage());
			}
		});
	}

	private List<Long> extractSeatIds(PendingBooking pendingBooking) {
		return pendingBooking.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}

	/**
	 * 좌석 ID 목록에서 trainCarId 추출
	 * 같은 CarType의 좌석들은 모두 같은 객차에 속하므로 첫 번째 좌석의 trainCarId 반환
	 */
	private Long getTrainCarId(List<Long> seatIds) {
		List<Seat> seats = seatRepository.findAllByIdWithTrainCar(seatIds);
		if (seats.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}
		return seats.get(0).getTrainCar().getId();
	}
}
