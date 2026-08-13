package com.sudo.raillo.booking.application.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatOccupancy;
import com.sudo.raillo.booking.domain.status.SeatOccupancyStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.Seat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SeatOccupancyService {

	private final SeatOccupancyRepository seatOccupancyRepository;

	public void hold(Reservation reservation, List<Seat> seats) {
		Long trainScheduleId = reservation.getTrainSchedule().getId();
		int departureStopOrder = reservation.getDepartureStopOrder();
		int arrivalStopOrder = reservation.getArrivalStopOrder();
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();

		// 1. 만료된 점유 행 선삭제 - 없으면 유니크 제약이 재점유를 막는다
		int deleted = seatOccupancyRepository.deleteExpiredInSections(
			trainScheduleId, seatIds, departureStopOrder, arrivalStopOrder - 1, LocalDateTime.now());
		if (deleted > 0) {
			log.debug("[만료 좌석 점유 정리] trainScheduleId={}, seatIds={}, deleted={}",
				trainScheduleId, seatIds, deleted);
		}

		// 2. 구간 전개 (좌석 ID, 구간 순으로 정렬해 락 획득 순서를 고정 → 데드락 확률 감소)
		List<SeatOccupancy> occupancies = expandToSections(reservation, seats, departureStopOrder, arrivalStopOrder);

		// 3. 삽입 - 유니크 제약 위반이 곧 좌석 충돌
		try {
			seatOccupancyRepository.saveAll(occupancies);
			seatOccupancyRepository.flush();
		} catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
			log.warn("[좌석 점유 충돌] trainScheduleId={}, seatIds={}, stopOrder={}->{}, cause={}",
				trainScheduleId, seatIds, departureStopOrder, arrivalStopOrder, e.getClass().getSimpleName());
			throw new BusinessException(BookingError.SEAT_ALREADY_OCCUPIED);
		}

		log.info("[좌석 점유 완료] reservationId={}, trainScheduleId={}, seatCount={}, occupancyRows={}",
			reservation.getId(), trainScheduleId, seats.size(), occupancies.size());
	}

	public void confirm(Reservation reservation, Booking booking, int seatCount) {
		int sectionCount = reservation.getArrivalStopOrder() - reservation.getDepartureStopOrder();
		int expectedRows = seatCount * sectionCount;

		int affected = seatOccupancyRepository.confirmByReservationId(
			reservation.getId(),
			booking,
			SeatOccupancyStatus.HELD,
			SeatOccupancyStatus.CONFIRMED,
			SeatOccupancy.NEVER_EXPIRES,
			LocalDateTime.now()
		);

		if (affected != expectedRows) {
			log.error("[좌석 점유 확정 실패] reservationId={}, expectedRows={}, affected={}",
				reservation.getId(), expectedRows, affected);
			throw new BusinessException(BookingError.RESERVATION_EXPIRED);
		}

		log.info("[좌석 점유 확정] reservationId={}, bookingId={}, rows={}",
			reservation.getId(), booking.getId(), affected);
	}

	public void releaseByReservationIds(List<Long> reservationIds) {
		if (reservationIds.isEmpty()) {
			return;
		}

		int deleted = seatOccupancyRepository.deleteByReservationIdIn(reservationIds);
		log.info("[좌석 점유 해제] reservationIds={}, deletedRows={}", reservationIds, deleted);
	}

	public void releaseByBookingId(Long bookingId) {
		int deleted = seatOccupancyRepository.deleteByBookingId(bookingId);
		log.info("[좌석 점유 해제] bookingId={}, deletedRows={}", bookingId, deleted);
	}

	private List<SeatOccupancy> expandToSections(
		Reservation reservation,
		List<Seat> seats,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		return seats.stream()
			.sorted(Comparator.comparing(Seat::getId))
			.flatMap(seat -> IntStream.range(departureStopOrder, arrivalStopOrder)
				.mapToObj(sectionOrder -> SeatOccupancy.createHeld(reservation, seat, sectionOrder)))
			.toList();
	}
}
