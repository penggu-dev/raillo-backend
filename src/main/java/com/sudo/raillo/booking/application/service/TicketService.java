package com.sudo.raillo.booking.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.domain.Qr;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ticket.TicketRepository;
import com.sudo.raillo.booking.infrastructure.ticket.TicketRepositoryCustom;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.Seat;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketService {

	private final QrService qrService;
	private final MemberRepository memberRepository;
	private final TicketRepository ticketRepository;
	private final TicketRepositoryCustom ticketRepositoryCustom;

	/***
	 * 티켓을 생성하는 메서드
	 * @param reservation 예약 정보
	 * @param passengerType 승객 유형
	 */
	public void createTicket(Reservation reservation, Seat seat, PassengerType passengerType) {
		Qr qr = qrService.createQr();
		Ticket ticket = Ticket.builder()
			.reservation(reservation)
			.seat(seat)
			.qr(qr)
			.passengerType(passengerType)
			.ticketStatus(TicketStatus.ISSUED)
			.build();
		ticketRepository.save(ticket);
	}

	public List<TicketReadResponse> getMyTickets(String username) {
		Member member = memberRepository.findByMemberNo(username)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
		return ticketRepositoryCustom.findPaidTicketResponsesByMemberId(member.getId());
	}

	@Transactional
	public void deleteTicketById(Long ticketId) {
		Ticket ticket = ticketRepository.findById(ticketId)
			.orElseThrow(() -> new BusinessException(BookingError.TICKET_NOT_FOUND));
		ticketRepository.delete(ticket);
	}

	@Transactional
	public void deleteTicketByReservationId(Long reservationId) {
		ticketRepository.deleteAllByReservationId(reservationId);
	}
}
