package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.projection.ReceiptProjection;
import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.booking.application.mapper.TicketMapper;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.TicketQueryRepository;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.Seat;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TicketService {

	private final MemberRepository memberRepository;
	private final TicketRepository ticketRepository;
	private final TicketQueryRepository ticketQueryRepository;
	private final SeatBookingRepository seatBookingRepository;
	private final BookingValidator bookingValidator;
	private final TicketMapper ticketMapper;

	/**
	 * 티켓을 생성하는 메서드
	 * @param booking 예약 정보
	 * @param seat 좌석 정보
	 * @param passengerType 승객 유형
	 * @param fare 운임
	 */
	public void createTicket(Booking booking, Seat seat, PassengerType passengerType, BigDecimal fare) {
		Ticket ticket = Ticket.builder()
			.booking(booking)
			.seat(seat)
			.passengerType(passengerType)
			.fare(fare)
			.ticketStatus(TicketStatus.ISSUED)
			.build();
		ticketRepository.save(ticket);
	}

	/**
	 * 영수증을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param ticketId 승차권 ID
	 */
	@Transactional(readOnly = true)
	public ReceiptResponse getReceipt(String memberNo, Long ticketId) {
		Member member = getMember(memberNo);
		Ticket ticket = getTicket(ticketId);
		bookingValidator.validateTicketOwner(ticket, member);

		ReceiptProjection receiptProjection = ticketQueryRepository.findReceiptByTicket(ticket)
			.orElseThrow(() -> new BusinessException(BookingError.RECEIPT_NOT_FOUND));

		return ticketMapper.convertToReceiptResponse(receiptProjection);
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
