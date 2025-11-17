package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.domain.Qr;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.TicketQueryRepository;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.Seat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TicketService {

	private final QrService qrService;
	private final MemberRepository memberRepository;
	private final TicketRepository ticketRepository;
	private final TicketQueryRepository ticketQueryRepository;

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

	@Transactional(readOnly = true)
	public List<TicketReadResponse> getMyTickets(String username) {
		Member member = memberRepository.findByMemberNo(username)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
		return ticketQueryRepository.findPaidTicketResponsesByMemberId(member.getId());
	}

	public void deleteTicketById(Long ticketId) {
		Ticket ticket = ticketRepository.findById(ticketId)
			.orElseThrow(() -> new BusinessException(BookingError.TICKET_NOT_FOUND));
		ticketRepository.delete(ticket);
	}

	public void deleteTicketByReservationId(Long reservationId) {
		ticketRepository.deleteAllByReservationId(reservationId);
	}
}
