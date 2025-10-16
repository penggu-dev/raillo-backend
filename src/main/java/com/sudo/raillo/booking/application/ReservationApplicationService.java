package com.sudo.raillo.booking.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.mapper.SeatPassengerMapper;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.infrastructure.SeatRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationApplicationService {

	private final ReservationService reservationService;
	private final SeatReservationService seatReservationService;
	private final TicketService ticketService;
	private final SeatPassengerMapper seatPassengerMapper;

	@Transactional
	public ReservationCreateResponse createReservation(ReservationCreateRequest request, String memberNo) {
		// TODO: 요청 파라미터를 여기서 모두 검증할지, 각 서비스에서 검증할지 결정 필요
		Reservation reservation = reservationService.createReservation(request, memberNo);
		validateStopSequence(reservation);

		// 승객 정보, 좌석 정보 정렬 (승객 정보는 PassengerType에 정의한 순서대로, 좌석 정보는 오름차순)
		List<PassengerSummary> passengers = new ArrayList<>(request.passengers());
		passengers.sort(Comparator.comparingInt(ps -> ps.getPassengerType().ordinal()));
		List<Long> seatIds = new ArrayList<>(request.seatIds());
		seatIds.sort(Comparator.naturalOrder());

		List<Long> seatReservationIds = seatPassengerMapper.mapSeatsToPassengers(reservation, passengers, seatIds);

		return new ReservationCreateResponse(reservation.getId(), seatReservationIds);
	}

	@Transactional
	public void cancelReservation(Reservation reservation) {
		Long reservationId = reservation.getId();
		seatReservationService.deleteSeatReservationByReservationId(reservationId);
		ticketService.deleteTicketByReservationId(reservationId);
	}

	@Transactional
	public void deleteReservationsByMember(Member member) {
		reservationService.deleteAllByMemberId(member.getId());
	}

	private static void validateStopSequence(Reservation reservation) {
		ScheduleStop departureStop = reservation.getDepartureStop();
		ScheduleStop arrivalStop = reservation.getArrivalStop();
		if (departureStop.getStopOrder() > arrivalStop.getStopOrder()) {
			throw new BusinessException(BookingError.TRAIN_NOT_OPERATIONAL);
		}
	}
}
