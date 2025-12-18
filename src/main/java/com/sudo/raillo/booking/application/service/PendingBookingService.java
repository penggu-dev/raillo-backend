package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingBookingService {

	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final BookingRedisRepository bookingRedisRepository;
	private final SeatRepository seatRepository;
	private final BookingValidator bookingValidator;

	/**
	 * 임시 예약을 생성하는 메서드
	 * @param request 임시 예약 요청 DTO
	 * @return 임시 예약
	 * */
	public PendingBooking createPendingBooking(
		PendingBookingCreateRequest request,
		String memberNo,
		BigDecimal totalFare
	) {
		TrainSchedule trainSchedule = getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = getStopStation(trainSchedule, request.arrivalStationId());

		// 열차 스케줄, 출발역, 도착역 검증
		bookingValidator.validateTrainOperating(trainSchedule);
		bookingValidator.validateSameSchedule(departureStop, arrivalStop);
		bookingValidator.validateStopSequence(departureStop, arrivalStop);
		// 승객 수와 좌석 수 일치 여부 검증
		bookingValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());

		List<PendingSeatBooking> pendingSeatBookings = createPendingSeatBookings(request.passengerTypes(),
			request.seatIds());

		PendingBooking pendingBooking = PendingBooking.create(
			memberNo,
			trainSchedule.getId(),
			departureStop.getId(),
			arrivalStop.getId(),
			pendingSeatBookings,
			totalFare
		);

		// redis 에 저장
		bookingRedisRepository.savePendingBooking(pendingBooking);

		return pendingBooking;
	}


	/**
	 * 여러 PendingBooking 한 번에 조회 및 검증
	 * - 모든 예약이 Redis에 존재해야 함
	 */
	@Transactional(readOnly = true)
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds) {
		Map<String, PendingBooking> bookingsById = bookingRedisRepository.getPendingBookingsAsMap(pendingBookingIds);

		validateAllPendingBookingsExist(pendingBookingIds, bookingsById);

		return pendingBookingIds.stream()
			.map(bookingsById::get)
			.toList();
	}

	private static void validateAllPendingBookingsExist(List<String> pendingBookingIds, Map<String, PendingBooking> bookingsById) {
		List<String> notFoundIds = pendingBookingIds.stream()
			.filter(id -> !bookingsById.containsKey(id))
			.toList();

		if (!notFoundIds.isEmpty()) {
			log.warn("[임시 예약 찾지 못함] pendingBookingIds={} - TTL 만료 또는 이미 사용됨", notFoundIds);
			throw new BusinessException(BookingError.PENDING_BOOKING_NOT_FOUND);
		}
	}

	// TODO: 객차 타입 검증 위치 조정 필요

	/**
	 * 객차 타입 조회
	 */
	public CarType findCarType(List<Long> seatIds) {
		if (seatIds.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		List<CarType> carTypes = seatRepository.findCarTypes(seatIds);

		if (carTypes.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		if (carTypes.size() != 1) {
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.get(0);
	}

	private List<PendingSeatBooking> createPendingSeatBookings(
		List<PassengerType> passengerTypes,
		List<Long> seatIds
	) {
		return IntStream.range(0, seatIds.size())
			.mapToObj(i -> new PendingSeatBooking(seatIds.get(i), passengerTypes.get(i)))
			.toList();
	}

	private ScheduleStop getStopStation(TrainSchedule trainSchedule, Long request) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), request)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}


	public void validatePendingBookingOwner(PendingBooking pendingBooking, String memberNo) {
		if (!pendingBooking.getMemberNo().equals(memberNo)) {
			log.error("[임시 예약 소유자 불일치] pendingBookingMemberNo={}, requestMemberNo={}",
				pendingBooking.getMemberNo(), memberNo);
			throw new BusinessException(BookingError.PENDING_BOOKING_ACCESS_DENIED);
		}
	}
}
