package com.sudo.raillo.booking.application.facade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.FareCalculationService;
import com.sudo.raillo.booking.application.service.SeatReservationService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.mapper.SeatPassengerMapper;
import com.sudo.raillo.booking.application.service.ReservationDeletionService;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationFacade {

	private final ReservationService reservationService;
	private final SeatReservationService seatReservationService;
	private final TicketService ticketService;
	private final ReservationDeletionService reservationDeletionService;
	private final FareCalculationService fareCalculationService;
	private final SeatPassengerMapper seatPassengerMapper;
	private final ReservationValidator reservationValidator;

	@Transactional
	public ReservationCreateResponse createReservation(ReservationCreateRequest request, String memberNo) {
		// TODO: 요청 파라미터를 여기서 모두 검증할지, 각 서비스에서 검증할지 결정 필요
		CarType carType = reservationService.findCarType(request.seatIds());
		BigDecimal totalFare = fareCalculationService.calculateFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengers(),
			carType
		);

		Reservation reservation = reservationService.createReservation(request, memberNo, totalFare);
		reservationValidator.validateStopSequence(reservation);

		// 승객 정보, 좌석 정보 정렬 (승객 정보는 PassengerType에 정의한 순서대로, 좌석 정보는 오름차순)
		List<PassengerSummary> passengers = new ArrayList<>(request.passengers());
		passengers.sort(Comparator.comparingInt(ps -> ps.getPassengerType().ordinal()));
		List<Long> seatIds = new ArrayList<>(request.seatIds());
		seatIds.sort(Comparator.naturalOrder());

		reservationValidator.validatePassengerSeatCount(passengers, seatIds);
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
		reservationDeletionService.deleteAllByMemberId(member.getId());
	}
}
