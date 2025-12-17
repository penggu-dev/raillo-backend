package com.sudo.raillo.booking.application.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.SeatPassengerPair;
import com.sudo.raillo.booking.application.mapper.SeatPassengerMapper;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.infrastructure.SeatRepository;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class SeatBookingService {

	private final SeatBookingRepository seatBookingRepository;
	private final SeatRepository seatRepository;
	private final BookingValidator bookingValidator;
	private final SeatPassengerMapper seatPassengerMapper;

	/***
	 * 새로운 좌석 예약 현황을 생성하고 예약하는 메서드
	 * @param booking Booking Entity
	 * @param seat Seat Entity
	 * @return SeatBooking Entity
	 */
	public SeatBooking reserveNewSeat(Booking booking, Seat seat, PassengerType passengerType) {
		try {
			Long trainScheduleId = booking.getTrainSchedule().getId();
			Long seatId = seat.getId();

			// 1. 먼저 좌석 자체에 비관적 락을 걸어 동시 접근 차단 (최우선 락)
			Seat lockedSeat = seatRepository.findByIdWithLock(seatId)
				.orElseThrow(() -> new BusinessException(BookingError.SEAT_NOT_FOUND));

			// 2. 락이 걸린 상태에서 해당 좌석의 기존 예약들을 비관적 락으로 조회
			List<SeatBooking> existingBookings = seatBookingRepository
				.findByTrainScheduleAndSeatWithLock(trainScheduleId, seatId);

			// 3. 락이 걸린 상태에서 충돌 검증 (원자성 보장)
			bookingValidator.validateConflictWithExistingBookings(booking, existingBookings);

			SeatBooking seatBooking = SeatBooking.create(
				booking.getTrainSchedule(),
				lockedSeat,
				booking,
				passengerType
			);
			return seatBookingRepository.save(seatBooking);
		} catch (OptimisticLockException | DataIntegrityViolationException e) {
			// 동시성 문제 및 유니크 제약 위반 발생
			throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
		}
	}

	public List<Long> createSeatBookings(
		Booking booking,
		List<PassengerSummary> passengers,
		List<Long> seatIds
	) {
		List<Seat> seats = seatRepository.findAllById(seatIds);
		List<SeatPassengerPair> pairs = seatPassengerMapper.mapSeatsToPassengers(passengers, seats);

		return pairs.stream()
			.map(pair -> reserveNewSeat(booking, pair.seat(), pair.passengerType()))
			.map(SeatBooking::getId)
			.toList();
	}

	public void deleteSeatBooking(Long seatBookingId) {
		SeatBooking seatBooking = seatBookingRepository.findById(seatBookingId)
			.orElseThrow(() -> new BusinessException(BookingError.SEAT_BOOKING_NOT_FOUND));
		seatBookingRepository.delete(seatBooking);
	}

	public void deleteSeatBookingByBookingId(Long bookingId) {
		seatBookingRepository.deleteAllByBookingId(bookingId);
	}
}
