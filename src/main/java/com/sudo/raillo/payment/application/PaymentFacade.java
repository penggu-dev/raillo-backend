package com.sudo.raillo.payment.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
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
	private final PendingBookingService pendingBookingService;
	private final SeatBookingService seatBookingService;
	private final TossPaymentClient tossPaymentClient;

	/**
	 * 결제 준비 처리
	 *
	 * 1. PendingBooking 조회 (BookingService)
	 * 2. Member 조회 및 소유자 검증
	 * 3. Order 생성 (PENDING) - OrderBooking, OrderSeatBooking도 함께 생성
	 * 4. Payment 생성 (PENDING)
	 * 5. orderId, amount 응답
	 */
	public PaymentPrepareResponse preparePayment(PaymentPrepareRequest request, String memberNo) {
		// 1. PendingBooking 목록 조회 및 검증 (존재하지 않으면 예외 발생)
		List<PendingBooking> pendingBookings = pendingBookingService.getPendingBookings(request.pendingBookingIds());

		// 2. Member 조회 및 모든 PendingBooking 소유자 검증
		Member member = memberService.getMemberByMemberNo(memberNo);
		pendingBookings.forEach(pendingBooking ->
			pendingBookingService.validatePendingBookingOwner(pendingBooking, memberNo)
		);

		// 3. Order 생성
		Order order = orderService.createOrder(memberNo, pendingBookings);

		// 4. 총 금액 계산
		BigDecimal totalAmount = pendingBookings.stream()
			.map(PendingBooking::getTotalFare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		// 5. Payment 생성 (PENDING)
		Payment payment = paymentService.createPayment(member, order, totalAmount);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, pendingBookingCount={}",
			order.getOrderCode(), payment.getId(), totalAmount, pendingBookings.size());

		// 6. orderId, amount 응답
		return new PaymentPrepareResponse(order.getOrderCode(), totalAmount);
	}


	/**
	 * 결제 승인 처리
	 *
	 * 1. Member, Order, Payment 조회
	 * 2. 소유자 검증 (Order, Payment 모두 요청한 Member 소유인지 확인)
	 * 3. 금액 3중 검증 (Client Request, Order, Payment 모두 일치 확인)
	 * 4. 상태 검증 (Payment 승인 가능 여부, 중복 결제 방지)
	 * 5. PaymentKey 저장 (별도 트랜잭션 - 무조건 커밋)
	 * 6. 토스페이먼츠 결제 승인 API 호출
	 *    - 성공: 다음 단계 진행
	 *    - 실패: 실패 정보 저장 (별도 트랜잭션 - 무조건 커밋) 후 예외 throw
	 * 7. 토스 응답 검증 (금액, paymentKey 일치 확인)
	 * 8. PaymentMethod 매핑
	 * 9. Booking & SeatBooking 생성 (BOOKED) - TODO
	 * 10. Payment 승인 처리 (PENDING → PAID, paymentMethod 저장)
	 * 11. Order 상태 변경 (PENDING → ORDERED) - TODO
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("결제 승인 시작: orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. Member, Order, Payment 조회
		Member member = memberService.getMemberByMemberNo(memberNo);
		Order order = orderService.getOrderByOrderCode(request.orderId());
		Payment payment = paymentService.getPaymentByOrder(order);

		// 2. 요청 전 검증
		orderService.validateOrderOwner(order, member);
		paymentService.validatePaymentOwner(payment, member);
		validateAmounts(request.amount(), order.getTotalAmount(), payment.getAmount());
		paymentService.validatePaymentApprovable(payment);
		paymentService.validateDuplicatePayment(order);

		// 3. 클라이언트에서 받은 PaymentKey 저장 (토스 승인 요청 전 별도 트랜잭션에서 무조건 커밋)
		paymentService.updatePaymentKeyInNewTransaction(payment.getId(), request.paymentKey());

		// 4. 토스페이먼츠 결제 승인 API 호출
		TossPaymentConfirmResponse result;
		try {
			result = tossPaymentClient.confirmPayment(request);
		} catch (TossPaymentException e) {
			paymentService.failPaymentInNewTransaction(payment.getId(), e.getErrorCode(), e.getMessage());

			log.info("[토스 결제 승인 실패] orderCode={}, httpStatus={}, tossCode={}, tossMessage={}",
				request.orderId(), e.getHttpStatus(), e.getErrorCode(), e.getMessage());

			throw e;
		}
		TossPaymentConfirmResponse tossResponse = result;


		// 5. 토스 응답 금액, paymentKey 재검증
		validateTossResponse(tossResponse, request);

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
		// order.complete();

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), request.orderId());

		return PaymentConfirmResponse.from(payment);
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
	 * 토스 응답 검증
	 * 1. 토스에서 응답한 금액과 요청 금액이 일치하는지 확인
	 * 2. 토스에서 응답한 paymentKey와 요청 paymentKey가 일치하는지 확인
	 */
	private void validateTossResponse(TossPaymentConfirmResponse tossResponse, PaymentConfirmRequest request) {
		BigDecimal tossAmount = BigDecimal.valueOf(tossResponse.totalAmount());
		if (tossAmount.compareTo(request.amount()) != 0) {
			log.error("[토스 응답 금액 불일치] tossAmount={}, requestAmount={}", tossAmount, request.amount());

			throw new BusinessException(
				PaymentError.PAYMENT_AMOUNT_MISMATCH,
				String.format("토스 결제 금액이 요청 금액과 일치하지 않습니다. (토스: %s, 요청: %s)", tossAmount, request.amount())
			);
		}

		if (!tossResponse.paymentKey().equals(request.paymentKey())) {
			log.error("[토스 응답 paymentKey 불일치] tossPaymentKey={}, requestPaymentKey={}",
				tossResponse.paymentKey(), request.paymentKey());

			throw new BusinessException(
				PaymentError.PAYMENT_KEY_MISMATCH,
				String.format("토스 결제 키가 요청 키와 일치하지 않습니다. (토스: %s, 요청: %s)", tossResponse.paymentKey(), request.paymentKey())
			);
		}

		log.debug("[토스 응답 검증 통과] paymentKey={}, amount={}", tossResponse.paymentKey(), tossAmount);
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
