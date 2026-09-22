package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingValidator {

	private final SeatBookingRepository seatBookingRepository;

	/**
	 * 승차권 소유자 검증
	 */
	public void validateTicketOwner(Ticket ticket, Member member) {
		if (!ticket.getBooking().getMember().getId().equals(member.getId())) {
			throw new BusinessException(BookingError.TICKET_ACCESS_DENIED);
		}
	}

	/** 결제 단계의 DB 방어. 예약 snapshot의 구간 순서를 사용한다. */
	public void validateSeatConflicts(List<Reservation> reservations) {
		for (var reservation : reservations) {
			if (!seatBookingRepository.findOverlappingSeatBookings(reservation.trainScheduleId(),
				reservation.getSeatIds(), reservation.departure().stopOrder(), reservation.arrival().stopOrder()).isEmpty()) {
				throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_BOOKING);
			}
		}
	}

	/**
	 * 결제 확정 시 확정 좌석 중 구간 중복 여부를 검증한다.
	 * <p>Repository는 구간이 겹치는 SeatBooking을 조회하고, 충돌 판단은 Validator에서 수행한다.</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStop 출발 정류장
	 * @param arrivalStop 도착 정류장
	 * @param seatIds 좌석 ID 목록
	 */
	public void validateSeatConflicts(
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Long> seatIds
	) {
		// DB에서 요청 구간과 겹치는 확정 좌석 조회
		List<SeatBooking> overlappingSeatBookings = seatBookingRepository.findOverlappingSeatBookings(
			trainScheduleId,
			seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);

		// 충돌 행이 있으면 예외 발생
		if (!overlappingSeatBookings.isEmpty()) {
			log.error("[구간 충돌] seatId={}, booked=[{}-{}], request=[{}-{}]",
				overlappingSeatBookings.get(0).getSeat().getId(),
				overlappingSeatBookings.get(0).getDepartureStopOrder(),
				overlappingSeatBookings.get(0).getArrivalStopOrder(),
				departureStop.getStopOrder(), arrivalStop.getStopOrder());
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_BOOKING);
		}
	}

}
