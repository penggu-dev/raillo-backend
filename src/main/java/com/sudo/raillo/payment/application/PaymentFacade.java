package com.sudo.raillo.payment.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
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
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.train.application.dto.TrainScheduleTimeInfo;
import com.sudo.raillo.train.application.service.TrainScheduleService;

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
	private final SeatHoldService seatHoldService;
	private final BookingService bookingService;
	private final TossPaymentClient tossPaymentClient;
	private final PaymentValidator paymentValidator;
	private final BookingValidator bookingValidator;
	private final TrainScheduleService trainScheduleService;

	/**
	 * 결제 준비 처리
	 *
	 * 1. PendingBooking 조회 (BookingService - 모든 Id 존재 확인, 소유자 검증)
	 * 2. Member 조회
	 * 3. Order 생성 (PENDING) - OrderBooking, OrderSeatBooking도 함께 생성
	 * 4. Payment 생성 (PENDING)
	 * 5. orderId, amount 응답
	 */
	public PaymentPrepareResponse preparePayment(PaymentPrepareRequest request, String memberNo) {
		List<PendingBooking> pendingBookings = pendingBookingService.getPendingBookings(request.pendingBookingIds(), memberNo);

		bookingValidator.validateSeatConflicts(pendingBookings);

		Member member = memberService.getMemberByMemberNo(memberNo);
		Order order = orderService.createOrder(memberNo, pendingBookings);
		Payment payment = paymentService.createPayment(member, order);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, pendingBookingCount={}",
			order.getOrderCode(), payment.getId(), order.getTotalAmount(), pendingBookings.size());

		return new PaymentPrepareResponse(order.getOrderCode(), order.getTotalAmount());
	}


	/**
	 * 결제 승인 처리
	 *
	 * 1. Order 조회 및 PendingBooking 유효성 검증 (TTL 만료 여부)
	 * 2. Member, Payment 조회
	 * 3. 소유자 검증 (Order, Payment 모두 요청한 Member 소유인지 확인)
	 * 4. 금액 3중 검증 및 중복 결제 방지
	 * 5. PaymentKey 저장 (별도 트랜잭션 - 무조건 커밋)
	 * 6. 토스페이먼츠 결제 승인 API 호출
	 *    - 성공: 다음 단계 진행
	 *    - 실패: 실패 정보 저장 (별도 트랜잭션 - 무조건 커밋) 후 예외 throw
	 * 7. 토스 응답 검증 (금액, paymentKey 일치 확인)
	 * 8. PaymentMethod 매핑
	 * 9. Order 상태 변경 (PENDING → ORDERED)
	 * 10. Booking & SeatBooking & Ticket 생성 (BOOKED)
	 * 11. Payment 승인 처리 (PENDING → PAID, paymentMethod 저장)
	 * 12. 좌석 확정 (Hold → Sold) 및 PendingBooking 정리
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("결제 승인 시작: orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. Order 조회 및 PendingBooking 유효성 검증 (TTL 만료 여부)
		Order order = orderService.getOrderByOrderCode(request.orderId());
		List<PendingBooking> pendingBookings = validateAndGetPendingBookings(order, memberNo);

		// 2. Member, Payment 조회
		Member member = memberService.getMemberByMemberNo(memberNo);
		Payment payment = paymentService.getPaymentByOrder(order);

		// 3. 요청 전 검증 (소유자, 금액, 중복결제)
		orderService.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(request.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);

		// 4. 클라이언트에서 받은 PaymentKey 저장 (토스 승인 요청 전 별도 트랜잭션에서 무조건 커밋)
		paymentService.updatePaymentKeyInNewTransaction(payment.getId(), request.paymentKey());

		// 5. 토스페이먼츠 결제 승인 API 호출
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


		// 6. 토스 응답 금액, paymentKey 재검증
		paymentValidator.validateTossResponseMatchesRequest(tossResponse, request);

		// 7. PaymentMethod 매핑
		PaymentMethod paymentMethod = mapToPaymentMethod(tossResponse.method());

		// 8. Order 상태 변경 (PENDING -> ORDERED)
		order.completePayment();

		// 9. Booking & SeatBooking 생성 (BOOKED)
		bookingService.createBookingFromOrder(order);

		// 10. Payment 승인 처리 (PENDING -> PAID, paymentMethod, paidAt 저장)
		payment.approve(paymentMethod);

		// 11. 좌석 확정 (Hold -> Sold) 및 PendingBooking 정리
		confirmSeatsAndCleanupPendingBookings(pendingBookings);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), request.orderId());

		return PaymentConfirmResponse.from(payment);
	}

	/**
	 * PendingBooking 유효성 검증 및 조회
	 *
	 * <p>결제 승인 전에 모든 PendingBooking이 살아있는지(TTL 만료되지 않았는지) 확인합니다.
	 * 하나라도 만료되었으면 결제를 진행할 수 없습니다.</p>
	 *
	 * @throws BusinessException PendingBooking이 없거나 일부가 만료된 경우
	 */
	private List<PendingBooking> validateAndGetPendingBookings(Order order, String memberNo) {
		List<String> pendingBookingIds = order.getPendingBookingIds();
		if (pendingBookingIds == null || pendingBookingIds.isEmpty()) {
			log.error("[PendingBooking 검증 실패] pendingBookingIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.PENDING_BOOKING_IDS_REQUIRED);
		}

		// 모든 PendingBooking이 존재하는지 검증 (getPendingBookings 내부에서 검증)
		return pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);
	}

	/**
	 * 좌석 확정 및 PendingBooking 정리
	 *
	 * <p>이미 검증된 PendingBooking에 대해 Hold → Sold 전환 및 삭제를 수행합니다.</p>
	 */
	private void confirmSeatsAndCleanupPendingBookings(List<PendingBooking> pendingBookings) {
		// 각 PendingBooking에 대해 좌석 확정
		for (PendingBooking pendingBooking : pendingBookings) {
			TrainScheduleTimeInfo timeInfo = trainScheduleService.getTrainScheduleTimeInfo(pendingBooking.getTrainScheduleId());
			seatHoldService.confirmSeats(pendingBooking, timeInfo);
		}

		// PendingBooking 삭제
		List<String> pendingBookingIds = pendingBookings.stream()
			.map(PendingBooking::getId)
			.toList();
		String memberNo = pendingBookings.get(0).getMemberNo();
		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);

		log.info("[PendingBooking 정리 완료] pendingBookingCount={}", pendingBookings.size());
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
