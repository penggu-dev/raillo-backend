package com.sudo.raillo.global.event.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.global.event.application.dto.BookingCreateFailedEvent;
import com.sudo.raillo.global.event.application.dto.PaymentCompletedEvent;
import com.sudo.raillo.global.event.domain.OutboxEvent;
import com.sudo.raillo.global.event.domain.OutboxStatus;
import com.sudo.raillo.global.event.exception.EventError;
import com.sudo.raillo.global.event.infrastructure.OutboxEventRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.dto.request.TossPaymentCancelRequest;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventHandler {

	private final BookingService bookingService;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final OutboxEventConverter outboxEventConverter;
	private final TossPaymentClient tossPaymentClient;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void handle(Long publishedEventId) {
		OutboxEvent event = outboxEventRepository.findByIdWithLock(publishedEventId)
			.orElseThrow(() -> new BusinessException(EventError.EVENT_NOT_FOUND));

		// 메서드로 분리 필요
		if(event.getStatus() != OutboxStatus.PENDING) {
			log.info("[이미 처리된 이벤트] eventId={}, status={}", event.getId(), event.getStatus());
			return;
		}

		switch (event.getEventType()) {
			case "PaymentCompletedEvent" -> handlePaymentCompleted(event);
			case "BookingCreateFailedEvent" -> handleBookingCreateFailed(event);
			default -> {
				log.error("[알 수 없는 이벤트 타입] eventType={}, eventId={}",
					event.getEventType(), event.getId());
				throw new BusinessException(EventError.UNKNOWN_EVENT);
			}
		}

		// 완료 후 이벤트 상태 변경
		event.complete();
	}

	/**
	 * 결제 승인 이벤트 처리
	 */
	private void handlePaymentCompleted(OutboxEvent event) {
		PaymentCompletedEvent payload = outboxEventConverter.deserialize(
			event,
			PaymentCompletedEvent.class
		);

		try {
			Order order = getOrder(payload.orderId());
			bookingService.createBookingFromOrder(order);

			// booking 생성 성공 후 해당 주문건 상태 완료로 변경
			order.completePayment();

		} catch (Exception e) { // 실패 시 보상 이벤트 발행
			log.error("[Booking 생성 실패] orderId={}", payload.orderId(), e);

			BookingCreateFailedEvent failedEvent = new BookingCreateFailedEvent(
				payload.orderId(),
				payload.paymentId(),
				payload.PaymentKey()
			);

			OutboxEvent failedEventOutboxEvent = OutboxEvent.create(
				"Order",
				payload.orderId(),
				"BookingCreateFailedEvent",
				outboxEventConverter.serialize(failedEvent)
			);
			outboxEventRepository.save(failedEventOutboxEvent);

			// 예외 던지면 트랜잭션이 롤백되어 저장한 보상 이벤트도 같이 사라지므로 예외 생략
		}
	}

	/**
	 * Booking 생성 실패 보상 이벤트 처리
	 */
	private void handleBookingCreateFailed(OutboxEvent event) {
		BookingCreateFailedEvent payload = outboxEventConverter.deserialize(
			event,
			BookingCreateFailedEvent.class
		);

		Order order = getOrder(payload.orderId());
		Payment payment = getPayment(payload.paymentId());

		// 1. Booking 생성에 실패한 건에 대해 토스 결제 취소 (환불)
		try {
			tossPaymentClient.cancelPayment(
				payload.PaymentKey(),
				new TossPaymentCancelRequest("예약 생성 실패로 인한 자동 취소", null)
			);
		} catch (Exception e) {
			log.warn("[토스 결제 취소 실패] orderId={}, paymentId={}, paymentKey={}]",
				payload.orderId(), payload.paymentId(), payload.PaymentKey(), e);
		}

		// 2. Order, Payment 상태 변경 - 해당 객체 재사용 x
		order.expired();
		payment.refund();
	}

	private Order getOrder(Long orderId) {
		return orderRepository.findById(orderId)
			.orElseThrow(() -> new BusinessException(OrderError.ORDER_NOT_FOUND));
	}

	private Payment getPayment(Long paymentId) {
		return paymentRepository.findById(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}

}
