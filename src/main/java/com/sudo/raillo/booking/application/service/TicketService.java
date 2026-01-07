package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Booking;
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

	private final MemberRepository memberRepository;
	private final TicketRepository ticketRepository;
	private final TicketQueryRepository ticketQueryRepository;
	private final BookingValidator bookingValidator;

	/**
	 * 티켓을 생성하는 메서드
	 * @param booking 예약 정보
	 * @param passengerType 승객 유형
	 */
	public void createTicket(Booking booking, Seat seat, PassengerType passengerType) {
		Ticket ticket = Ticket.builder()
			.booking(booking)
			.seat(seat)
			.passengerType(passengerType)
			.ticketStatus(TicketStatus.ISSUED)
			.build();
		ticketRepository.save(ticket);
	}

	/**
	 * 영수증을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param ticketId 티켓 ID
	 */
	@Transactional(readOnly = true)
	public ReceiptResponse getReceipt(String memberNo, Long ticketId) {
		Member member = getMember(memberNo);
		Ticket ticket = getTicket(ticketId);
		bookingValidator.validateTicketOwner(ticket, member);

		return ticketQueryRepository.findReceiptByTicket(ticket);
	}

	@Transactional(readOnly = true)
	public List<TicketReadResponse> getMyTickets(String memberNo) {
		Member member = getMember(memberNo);
		return ticketQueryRepository.findPaidTicketResponsesByMemberId(member.getId());
	}

	public void deleteTicketById(Long ticketId) {
		Ticket ticket = getTicket(ticketId);
		ticketRepository.delete(ticket);
	}

	public void deleteTicketByBookingId(Long bookingId) {
		ticketRepository.deleteAllByBookingId(bookingId);
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}

	private Ticket getTicket(Long ticketId) {
		return ticketRepository.findById(ticketId)
			.orElseThrow(() -> new BusinessException(BookingError.TICKET_NOT_FOUND));
	}
}
