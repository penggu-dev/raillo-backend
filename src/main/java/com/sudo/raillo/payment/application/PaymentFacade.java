package com.sudo.raillo.payment.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
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
import com.sudo.raillo.payment.application.validator.PaymentValidator;
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
	private final BookingService bookingService;
	private final TossPaymentClient tossPaymentClient;
	private final BookingValidator bookingValidator;
	private final PaymentValidator paymentValidator;

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
			bookingValidator.validatePendingBookingOwner(pendingBooking, memberNo)
		);

		// 3. Order 생성 (totalAmount 계산 포함)
		Order order = orderService.createOrder(memberNo, pendingBookings);

		// 4. Payment 생성 (PENDING, totalAmount는 Order에서 가져옴)
		Payment payment = paymentService.createPayment(member, order);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, pendingBookingCount={}",
			order.getOrderCode(), payment.getId(), order.getTotalAmount(), pendingBookings.size());

		// 5. orderId, amount 응답
		return new PaymentPrepareResponse(order.getOrderCode(), order.getTotalAmount());
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
	 * 9. Order 상태 변경 (PENDING → ORDERED)
	 * 10. Booking & SeatBooking 생성 (BOOKED)
	 * 11. Payment 승인 처리 (PENDING → PAID, paymentMethod 저장)
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("결제 승인 시작: orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. Member, Order, Payment 조회
		Member member = memberService.getMemberByMemberNo(memberNo);
		Order order = orderService.getOrderByOrderCode(request.orderId());
		Payment payment = paymentService.getPaymentByOrder(order);

		// 2. 요청 전 검증 (소유자, 금액, 중복결제)
		orderService.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(request.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);

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
		paymentValidator.validateTossResponseMatchesRequest(tossResponse, request);

		// 6. PaymentMethod 매핑
		PaymentMethod paymentMethod = mapToPaymentMethod(tossResponse.method());

		// 7. Order 상태 변경 (PENDING -> ORDERED)
		order.completePayment();

		// 8. Booking & SeatBooking 생성 (BOOKED)
		bookingService.createBookingFromOrder(order);

		// 9. Payment 승인 처리 (PENDING -> PAID, paymentMethod, paidAt 저장)
		payment.approve(paymentMethod);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), request.orderId());

		return PaymentConfirmResponse.from(payment);
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
