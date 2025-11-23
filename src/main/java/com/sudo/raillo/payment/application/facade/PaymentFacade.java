package com.sudo.raillo.payment.application.facade;

import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.PaymentService;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessCardRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentProcessResponse;
import com.sudo.raillo.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PaymentFacade {

	private final PaymentService paymentService;
	private final ReservationService reservationService;
	private final TicketService ticketService;

	public PaymentProcessResponse processPaymentWithCard(String memberNo, PaymentProcessCardRequest request) {
		// 결제 처리
		Payment payment = paymentService.processPayment();

		// 결제 처리 완료된 후 예약 & 티켓 생성
		Reservation reservation = reservationService.createReservation(payment);
		ticketService.createTicket(reservation);
		return PaymentProcessResponse.from(payment);
	}
}
