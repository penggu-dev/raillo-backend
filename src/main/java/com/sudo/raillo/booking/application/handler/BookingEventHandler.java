package com.sudo.raillo.booking.application.handler;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.event.PaymentCompletedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventHandler {

	private final BookingService bookingService;
	private final OrderService orderService;
	private final BookingRepository bookingRepository;

	@EventListener(classes = PaymentCompletedEvent.class)
	@Transactional
	public void handlePaymentCompleted(PaymentCompletedEvent event) {
		// 멱등성 검증: 이미 처리된 이벤트인지 확인
		if (bookingRepository.existsByOrderId(event.orderId())) {
			log.info("[이벤트 중복 처리 스킵] orderId={}", event.orderId());
			return;
		}

		Order order = orderService.getOrderById(event.orderId());
		List<Booking> bookings = bookingService.createBookingFromOrder(order);
		log.info("[이벤트 처리 완료] orderId={}, bookingCount={}", event.orderId(), bookings.size());
	}
}
