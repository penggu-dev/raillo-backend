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
	 * 1. Member, Order, Payment 조회
	 * 2. 소유자 검증 (Order, Payment 모두 동일 Member 소유)
	 * 3. 금액 검증 (Client Request, Order, Payment 모두 일치)
	 * 4. 상태 검증 (Payment 승인 가능, 중복 결제 방지)
	 * 5. 토스페이먼츠 결제 승인 API 호출
	 * 6. 토스 응답 금액 재검증
	 * 7. 토스 응답 Payment 객체의 paymentKey,
	 * 7. Booking & SeatBooking 생성 (BOOKED)
	 * 8. Payment 승인 처리 (PENDING → PAID, paymentKey, paymentMethod 저장)
	 * 9. Order 상태 변경 (PENDING → ORDERED)
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("결제 승인 시작: orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. Member, Order, Payment 조회
		Member member = memberService.getMemberByMemberNo(memberNo);
		Order order = orderService.getOrderByOrderCode(request.orderId());
		Payment payment = paymentService.getPaymentByOrder(order);

		// 2. 요청 전 검증
		validateOwnership(order, payment, member);
		validateAmounts(request.amount(), order.getTotalAmount(), payment.getAmount());
		paymentService.validatePaymentApprovable(payment);
		paymentService.validateDuplicatePayment(order);

		// 3. 클라이언트에서 받은 paymentKey 저장 (토스 승인 요청 전)
		payment.updatePaymentKey(request.paymentKey());

		// 4. 토스페이먼츠 결제 승인 API 호출
		TossPaymentConfirmResponse result;
		try {
			result = tossPaymentClient.confirmPayment(request);
		} catch (TossPaymentException e) {
			payment.fail(e.getErrorCode(), e.getMessage());

			log.info("[토스 결제 승인 실패] orderCode={}, httpStatus={}, tossCode={}, tossMessage={}",
				request.orderId(), e.getHttpStatus(), e.getErrorCode(), e.getMessage());

			throw e;
		}
		TossPaymentConfirmResponse tossResponse = result;


		// 5. 토스 응답 금액 재검증
		validateTossResponseAmount(tossResponse, request.amount());

		// 6. PaymentMethod 매핑
		PaymentMethod paymentMethod = mapToPaymentMethod(tossResponse.method());

		// 7. OrderBooking -> Booking & SeatBooking 생성
		// TODO: Booking 관련 로직 구현 필요
		/*
		List<OrderBooking> orderBookings = order.getOrderBookings();
		for (OrderBooking orderBooking : orderBookings) {
			Booking booking = bookingService.createBookingFromOrder(orderBooking);
			List<OrderSeatBooking> orderSeatBookings = orderBooking.getOrderSeatBookings();
			for (OrderSeatBooking orderSeatBooking : orderSeatBookings) {
				seatBookingService.createSeatBookingFromOrder(booking, orderSeatBooking);
			}
		}
		*/

		// 8. Payment 승인 처리 (PENDING -> PAID, paymentMethod 저장)
		payment.approve(paymentMethod);

		// 9. Order 상태 변경 (PENDING -> ORDERED)
		// orderService.completeOrder(order);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), request.orderId());

		return PaymentConfirmResponse.from(payment);
	}

	/**
	 * 소유자 검증
	 * Order와 Payment 모두 요청한 Member의 소유인지 확인
	 */
	private void validateOwnership(Order order, Payment payment, Member member) {
		if (!order.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Order의 소유자가 아님: orderCode={}, requestMemberId={}, orderMemberId={}",
				order.getOrderCode(), member.getId(), order.getMember().getId());
			throw new BusinessException(PaymentError.ORDER_ACCESS_DENIED);
		}

		if (!payment.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Payment의 소유자가 아님: paymentId={}, requestMemberId={}, paymentMemberId={}",
				payment.getId(), member.getId(), payment.getMember().getId());
			throw new BusinessException(PaymentError.PAYMENT_ACCESS_DENIED);
		}
	}

	/**
	 * 금액 3중 검증
	 * 1. 클라이언트 요청 금액 vs Order 금액
	 * 2. Order 금액 vs Payment 금액
	 * 3. 모두 일치해야 진행
	 */
	private void validateAmounts(BigDecimal requestAmount, BigDecimal orderAmount, BigDecimal paymentAmount) {
		// 1. 요청 금액 vs Order 금액
		if (requestAmount.compareTo(orderAmount) != 0) {
			log.error("[금액 불일치] 요청 금액 != Order 금액: requestAmount={}, orderAmount={}", requestAmount, orderAmount);
			throw new BusinessException(PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		// 2. Order 금액 vs Payment 금액
		if (orderAmount.compareTo(paymentAmount) != 0) {
			log.error("[금액 불일치] Order 금액 != Payment 금액: orderAmount={}, paymentAmount={}", orderAmount, paymentAmount);
			throw new BusinessException(PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		log.debug("[금액 검증 통과] requestAmount={}, orderAmount={}, paymentAmount={}", requestAmount, orderAmount, paymentAmount);
	}

	/**
	 * 토스 응답 금액 검증
	 * 토스에서 응답한 금액과 요청 금액이 일치하는지 확인
	 */
	private void validateTossResponseAmount(TossPaymentConfirmResponse tossResponse, BigDecimal requestAmount) {
		BigDecimal tossAmount = BigDecimal.valueOf(tossResponse.totalAmount());
		if (tossAmount.compareTo(requestAmount) != 0) {
			log.warn("토스 응답 금액 불일치: tossAmount={}, requestAmount={}", tossAmount, requestAmount);

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
