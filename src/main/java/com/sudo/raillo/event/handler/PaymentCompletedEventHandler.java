package com.sudo.raillo.event.handler;

import org.springframework.stereotype.Component;

import com.sudo.raillo.event.payload.PaymentCompletedPayload;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 완료 이벤트 핸들러
 * Payment 결제 완료 후 Booking/Ticket 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedEventHandler
	implements OutboxEventHandler<PaymentCompletedPayload> {

	private final BookingFacade bookingFacade;
	private final PaymentRepository paymentRepository;

	@Override
	public String getEventType() {
		return "PaymentCompletedEvent";
	}

	@Override
	public Class<PaymentCompletedPayload> getPayloadClass() {
		return PaymentCompletedPayload.class;
	}

	@Override
	public void handle(PaymentCompletedPayload payload) {
		Long paymentId = payload.getPaymentId();

		// 멱등성 체크: 이미 Booking이 생성되어 있으면 스킵
		if (bookingFacade.existsByPaymentId(paymentId)) {
			log.info("이미 처리된 결제 - paymentId: {}", paymentId);
			return;
		}

		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new IllegalStateException(
				"Payment not found: " + paymentId));

		// Booking, Ticket, SeatBooking 생성
		bookingFacade.createBookingAndTickets(payment);

		log.info("예매 생성 완료 - paymentId: {}, memberId: {}",
			paymentId, payload.getMemberId());
	}
}
