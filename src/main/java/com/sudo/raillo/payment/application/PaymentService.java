package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.application.facade.BookingFacade;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.PaymentInfo;
import com.sudo.raillo.payment.application.dto.projection.PaymentProjection;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessAccountRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessCardRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentCancelResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentHistoryResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentProcessResponse;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.domain.type.PaymentMethod;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.infrastructure.PaymentQueryRepository;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;
import com.sudo.raillo.payment.util.PaymentKeyGenerator;
import com.sudo.raillo.train.infrastructure.SeatBookingQueryRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

	private final BookingRepository bookingRepository;
	private final SeatBookingQueryRepository seatBookingQueryRepository;
	private final MemberRepository memberRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentQueryRepository paymentQueryRepository;
	private final PaymentKeyGenerator paymentKeyGenerator;
	private final BookingFacade bookingFacade;
	private final TicketService ticketService;

	/**
	 * 결제 내역 조회
	 * @param memberNo 회원번호
	 * @return {@code List<PaymentHistoryResponse>}
	 */
	@Transactional(readOnly = true)
	public List<PaymentHistoryResponse> getPaymentHistory(String memberNo) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		List<PaymentProjection> paymentProjections = paymentQueryRepository.findPaymentHistoryByMemberId(
			member.getId());

		return paymentProjections.stream()
			.map(paymentProjection -> new PaymentHistoryResponse(
				paymentProjection.getPaymentId(), paymentProjection.getPaymentKey(),
				paymentProjection.getBookingCode(), paymentProjection.getAmount(),
				paymentProjection.getPaymentMethod(), paymentProjection.getPaymentStatus(),
				paymentProjection.getPaidAt(), paymentProjection.getCancelledAt(), paymentProjection.getRefundedAt()))
			.toList();
	}

	public PaymentCancelResponse cancelPayment(String memberNo, String paymentKey) {
		Payment payment = findPayment(memberNo, paymentKey);

		// 결제 취소 처리
		// payment.cancel("사용자 요청에 의한 취소");
		//
		// markBookingAsCancelled(payment.getBooking());
		//
		// log.info("결제 취소 완료: paymentKey={}, bookingId={}", paymentKey, payment.getBooking().getId());
		//
		// // 즉각 환불 처리 (임시)
		// refundPayment(payment, payment.getBooking());

		return PaymentCancelResponse.from(payment);
	}

	/**
	 * 결제 처리 (공통)
	 *
	 * @param memberNo 회원번호
	 * @param request {@link PaymentProcessRequest} 객체
	 * @return {@link PaymentProcessResponse} 객체
	 */
	private PaymentProcessResponse processPayment(String memberNo, PaymentProcessRequest request, Order order) {
		Payment payment = createAndSavePayment(request, memberNo, order);

		// validatePaymentApprovalConditions(payment, booking);
		// executePaymentApproval(payment, booking);
		// completePaymentProcess(request, payment, booking);

		return PaymentProcessResponse.from(payment);
	}

	private Booking getBooking(Long bookingId) {
		return bookingRepository.findById(bookingId)
			.orElseThrow(() -> new BusinessException(PaymentError.BOOKING_NOT_FOUND));
	}

	private Payment createAndSavePayment(PaymentProcessRequest request, String memberNo, Order order) {
		// TODO : Member는 Facade 상위로 옮겨가는 게 나을 것 같음
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		String paymentKey = paymentKeyGenerator.generatePaymentKey(memberNo);
		PaymentInfo paymentInfo = new PaymentInfo(request.getAmount(), request.getPaymentMethod(),
			PaymentStatus.PENDING);
		Payment payment = Payment.create(member, order, paymentKey, paymentInfo);

		return paymentRepository.save(payment);
	}

	public void approvePayment(Payment payment, String paymentKey, PaymentMethod paymentMethod) {
		payment.approve(paymentKey, paymentMethod);
	}

	private void validatePaymentProcessRequest(PaymentProcessRequest request, Booking booking) {
		// 금액 위변조 검증
//		if (request.getAmount().compareTo(booking.getTotalFare()) != 0) {
//			throw new BusinessException(PaymentError.PAYMENT_AMOUNT_MISMATCH);
//		}

		// 중복 결제 검증
		if (paymentRepository.existsByBookingIdAndPaymentStatus(request.getBookingId(), PaymentStatus.PAID)) {
			throw new BusinessException(PaymentError.PAYMENT_ALREADY_COMPLETED);
		}
	}

	private void validatePaymentApprovalConditions(Payment payment, Booking booking) {
		// 결제 상태 검증
		if (!payment.canBePaid()) {
			throw new BusinessException(PaymentError.PAYMENT_NOT_APPROVABLE);
		}

		// 예약 상태 재검증 (동시성 문제 방지)
//		if (!booking.canBePaid()) {
//			throw new BusinessException(PaymentError.BOOKING_NOT_PAYABLE);
//		}
	}

	private void executePaymentApproval(Payment payment, Booking booking) {
		// 결제 승인 처리
		// payment.approve();

		// 예약 상태 변경
		markBookingAsPaid(booking);
	}

	public void approvePayment(Payment payment, String paymentKey, PaymentMethod paymentMethod, Booking booking) {
		payment.approve(paymentKey, paymentMethod, booking);
	}

	public void failPayment(Payment payment, String failureCode, String failureMessage) {
		payment.fail(failureCode, failureMessage);
	}

	// TODO : 취소를 위한 결제 조회로 메서드명 변경 필요
	private Payment findPayment(String memberNo, String paymentKey) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		Payment payment = paymentRepository.findByPaymentKey(paymentKey)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));

		validatePaymentCancellableCondition(member, payment);

		return payment;
	}

	private void validatePaymentCancellableCondition(Member member, Payment payment) {
		// 결제 소유자 확인
		if (!payment.getMember().getId().equals(member.getId())) {
			throw new BusinessException(PaymentError.PAYMENT_ACCESS_DENIED);
		}

		// 결제 취소 가능 여부 확인
		if (!payment.canBeCancelled()) {
			throw new BusinessException(PaymentError.PAYMENT_NOT_CANCELLABLE);
		}

		// 예약 취소 가능 여부 확인
		//		if (!payment.getBooking().canBeCancelled()) {
		//			throw new BusinessException(BookingError.BOOKING_DELETE_FAILED);
		//		}
	}

	/**
	 * 환불 처리
	 * @param payment {@link Payment} 객체
	 */
	private void refundPayment(Payment payment, Booking booking) {
		// 해당 로직은 외부 엔드포인트가 존재하지 않고 추후 엔드포인트가 생긴다고 하더라도
		// 신뢰할 수 있는 PG사에서 환불 완료된 결제에 대해서만 호출할 예정이기 때문에 검증 과정이 필요 없습니다.

		// 즉각 환불 처리 로직 (임시)
		if (payment.canBeRefunded()) {
			payment.refund();
		}

		log.info("환불 처리 완료: paymentKey={}", payment.getPaymentKey());
	}

	// --------- Toss 리팩토링 이후 만든 메서드들 --------- //
	public Payment getPaymentByOrder(Order order) {
		return paymentRepository.findByOrder(order)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}

	/**
	 * Payment 소유자 검증
	 */
	public void validatePaymentOwner(Payment payment, Member member) {
		if (!payment.getMember().getId().equals(member.getId())) {
			throw new BusinessException(PaymentError.PAYMENT_ACCESS_DENIED);
		}
	}

	/**
	 * Payment 상태 검증 (승인 가능한 상태인지)
	 */
	public void validatePaymentApprovable(Payment payment) {
		if (!payment.canBePaid()) {
			log.info("승인 불가능한 결제 상태: paymentId={}, status={}", payment.getId(), payment.getPaymentStatus());
			throw new BusinessException(PaymentError.PAYMENT_NOT_APPROVABLE);
		}
	}

	/**
	 * 중복 결제 검증
	 */
	public void validateDuplicatePayment(Order order) {
		boolean exists = paymentRepository.existsByOrderAndPaymentStatus(order, PaymentStatus.PAID);

		if (exists) {
			throw new BusinessException(PaymentError.PAYMENT_ALREADY_COMPLETED);
		}
	}
}
