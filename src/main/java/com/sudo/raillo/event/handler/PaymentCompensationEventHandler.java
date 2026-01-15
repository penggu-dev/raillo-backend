package com.sudo.raillo.event.handler;

import org.springframework.stereotype.Component;

import com.sudo.raillo.event.payload.PaymentCompletedPayload;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 보상 이벤트 핸들러 (자동 환불)
 * PaymentCompletedEvent가 DEAD 상태가 되면 자동으로 환불 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationEventHandler
	implements OutboxEventHandler<PaymentCompletedPayload> {

	private final PaymentRepository paymentRepository;
	private final PaymentRefundService paymentRefundService;
	private final NotificationService notificationService;

	@Override
	public String getEventType() {
		return "PaymentCompensationEvent";
	}

	@Override
	public Class<PaymentCompletedPayload> getPayloadClass() {
		return PaymentCompletedPayload.class;
	}

	@Override
	public void handle(PaymentCompletedPayload payload) {
		Long paymentId = payload.getPaymentId();

		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new IllegalStateException(
				"Payment not found: " + paymentId));

		// 멱등성 체크: 이미 환불되었으면 스킵
		if (payment.isRefunded()) {
			log.info("이미 환불된 결제 - paymentId: {}", paymentId);
			return;
		}

		// 토스 환불 API 호출 & Payment 상태 변경
		paymentRefundService.refund(payment, "예매 생성 실패로 인한 자동 환불");

		// 고객 알림 발송
		notificationService.sendRefundNotification(payment);

		log.info("자동 환불 완료 - paymentId: {}, amount: {}",
			paymentId, payload.getAmount());
	}
}
