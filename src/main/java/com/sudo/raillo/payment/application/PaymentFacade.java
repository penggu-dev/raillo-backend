package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.SeatBookingService;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.type.PaymentMethod;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentFacade {

	private final PaymentService paymentService;
	private final OrderService orderService;
	private final MemberService memberService;
	private final BookingService bookingService;
	private final SeatBookingService seatBookingService;
	private final TossPaymentClient tossPaymentClient;

	/**
	 * 결제 승인 처리
	 *
	 * 1. 클라이언트 요청 검증 (orderId, paymentKey, amount)
	 * 2. Order 조회 및 금액 검증
	 * 3. Payment 조회 및 상태 검증
	 * 4. 토스페이먼츠 결제 승인 API 호출
	 * 5. 토스 응답 금액 재검증
	 * 6. Booking 생성 (BOOKED)
	 * 7. SeatBooking 생성
	 * 8. Payment 승인 처리 (PENDING → PAID)
	 * 9. Order 상태 변경 (PENDING → ORDERED)
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("결제 승인 시작: orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. Member 조회
		Member member = memberService.getMemberByMemberNo(memberNo);

		// 2. Order 조회
		Order order = orderService.getOrderByOrderCode(request.orderId());

		// 3. Order  검증
		// orderService.validateOrderOwner(order, member);
		// orderService.validateOrderPayable(order);
		validateOrderAmount(order, request.amount());

		// 4. Payment 조회
		Payment payment = paymentService.getPaymentByOrder(order);

		// 5. Payment 검증
		paymentService.validatePaymentApprovable(payment);
		validatePaymentAmount(payment, order.getTotalAmount());
		paymentService.validateDuplicatePayment(order);

		// 10. 토스페이먼츠 결제 승인 API 호출
		TossPaymentConfirmResponse tossResponse;
		try {
			tossResponse = tossPaymentClient.confirmPayment(request);
		} catch (TossPaymentException e) {
			log.error("[결제 실패] orderId={}, tossCode={}, tossMessage={}", request.orderId(), e.getErrorCode(), e.getMessage());
			paymentService.failPayment(payment, e.getErrorCode(), e.getMessage());
			throw e;
		}

		// 11. 토스 응답 금액 재검증
		validateTossResponseAmount(tossResponse, request.amount());

		// 12. PaymentMethod 매핑
		PaymentMethod paymentMethod = mapToPaymentMethod(tossResponse.method());

		// 13. OrderBooking -> Booking 생성 (각 OrderBooking마다 BOOKED 상태로 생성)
/*		List<OrderBooking> orderBookings = order.getOrderBookings();

		for (OrderBooking orderBooking : orderBookings) {
			// 13-1. Booking 생성 (BOOKED 상태로 바로 생성)
			Booking booking = bookingService.createBookingFromOrder(orderBooking);

			// 13-2. OrderSeatBooking -> SeatBooking 생성
			List<OrderSeatBooking> orderSeatBookings = orderBooking.getOrderSeatBookings();
			for (OrderSeatBooking orderSeatBooking : orderSeatBookings) {
				seatBookingService.createSeatBookingFromOrder(booking, orderSeatBooking);
			}
		}*/

		// 14. Payment 승인 처리 (PENDING -> PAID)
		paymentService.approvePayment(payment, request.paymentKey(), paymentMethod);

		log.info("결제 승인 완료: paymentId={}, orderId={}", payment.getId(), request.orderId());

		// 15. Order 상태 변경 (PENDING -> ORDERED)
		// orderService.completeOrder(order);

		return PaymentConfirmResponse.from(payment);
	}

	/**
	 * Order 금액 검증
	 * 클라이언트에서 전달한 금액과 실제 Order 금액이 일치하는지 확인
	 * (클라이언트 조작 방지)
	 */
	private void validateOrderAmount(Order order, BigDecimal requestAmount) {
		if (order.getTotalAmount().compareTo(requestAmount) != 0) {
			log.error("주문 금액 불일치: orderId={}, orderAmount={}, requestAmount={}",
				order.getOrderCode(), order.getTotalAmount(), requestAmount);
			throw new BusinessException(
				PaymentError.PAYMENT_AMOUNT_MISMATCH,
				String.format("결제 금액이 일치하지 않습니다. (주문금액: %s, 요청금액: %s)", order.getTotalAmount(), requestAmount)
			);
		}
	}

	/**
	 * Payment-Order 금액 일치 검증
	 */
	private void validatePaymentAmount(Payment payment, BigDecimal orderAmount) {
		if (payment.getAmount().compareTo(orderAmount) != 0) {
			log.error("Payment-Order 금액 불일치: paymentId={}, paymentAmount={}, orderAmount={}",
				payment.getId(), payment.getAmount(), orderAmount);
			throw new BusinessException(
				PaymentError.PAYMENT_AMOUNT_MISMATCH,
				String.format("결제 정보 금액이 주문 금액과 일치하지 않습니다. (결제: %s, 주문: %s)", payment.getAmount(), orderAmount)
			);
		}
	}

	/**
	 * 토스 응답 금액 검증
	 * 토스에서 응답한 금액과 요청 금액이 일치하는지 확인
	 */
	private void validateTossResponseAmount(TossPaymentConfirmResponse tossResponse, BigDecimal requestAmount) {
		BigDecimal tossAmount = BigDecimal.valueOf(tossResponse.totalAmount());
		if (tossAmount.compareTo(requestAmount) != 0) {
			log.error("토스 응답 금액 불일치: tossAmount={}, requestAmount={}",
				tossAmount, requestAmount);
			throw new BusinessException(
				PaymentError.PAYMENT_AMOUNT_MISMATCH,
				String.format("토스 결제 금액이 일치하지 않습니다. (토스: %s, 요청: %s)", tossAmount, requestAmount)
			);
		}
	}

	/**
	 * 토스 결제수단 문자열을 PaymentMethod enum으로 매핑
	 */
	private PaymentMethod mapToPaymentMethod(String tossMethod) {
		return switch (tossMethod) {
			case "카드" -> PaymentMethod.CREDIT_CARD;
			case "가상계좌" -> PaymentMethod.VIRTUAL_ACCOUNT;
			case "계좌이체" -> PaymentMethod.TRANSFER;
			case "휴대폰" -> PaymentMethod.MOBILE_PHONE;
			case "간편결제" -> PaymentMethod.EASY_PAY;
			case "문화상품권", "도서문화상품권", "게임문화상품권" -> PaymentMethod.GIFT_CERTIFICATE;
			default -> {
				log.warn("알 수 없는 결제수단: {}", tossMethod);
				throw new BusinessException(PaymentError.INVALID_PAYMENT_METHOD, "지원하지 않는 결제 수단입니다: " + tossMethod);
			}
		};
	}
}
