package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.application.facade.BookingFacade;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
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

		List<PaymentProjection> paymentProjections = paymentQueryRepository.findPaymentHistoryByMemberId(member.getId());

		return paymentProjections.stream()
			.map(paymentProjection -> new PaymentHistoryResponse(
				paymentProjection.getPaymentId(), paymentProjection.getPaymentKey(),
				paymentProjection.getBookingCode(), paymentProjection.getAmount(),
				paymentProjection.getPaymentMethod(), paymentProjection.getPaymentStatus(),
				paymentProjection.getPaidAt(), paymentProjection.getCancelledAt(), paymentProjection.getRefundedAt()))
			.toList();
	}

	/**
	 * 결제 처리 (카드)
	 *
	 * @param memberNo 회원번호
	 * @param request {@link PaymentProcessCardRequest} 객체
	 * @return {@link PaymentProcessResponse} 객체
	 */
	public PaymentProcessResponse processPaymentViaCard(String memberNo, PaymentProcessCardRequest request) {
		return processPayment(memberNo, request);
	}

	/**
	 * 결제 처리 (계좌 이체)
	 *
	 * @param memberNo 회원번호
	 * @param request {@link PaymentProcessAccountRequest} 객체
	 * @return {@link PaymentProcessResponse} 객체
	 */
	public PaymentProcessResponse processPaymentViaBankAccount(String memberNo, PaymentProcessAccountRequest request) {
		return processPayment(memberNo, request);
	}

	public PaymentCancelResponse cancelPayment(String memberNo, String paymentKey) {
		Payment payment = findPayment(memberNo, paymentKey);

		// 결제 취소 처리
		payment.cancel("사용자 요청에 의한 취소");

		markBookingAsCancelled(payment.getBooking());

		log.info("결제 취소 완료: paymentKey={}, bookingId={}", paymentKey, payment.getBooking().getId());

		// 즉각 환불 처리 (임시)
		refundPayment(payment, payment.getBooking());

		return PaymentCancelResponse.from(payment);
	}

	/**
	 * 결제 처리 (공통)
	 *
	 * @param memberNo 회원번호
	 * @param request {@link PaymentProcessRequest} 객체
	 * @return {@link PaymentProcessResponse} 객체
	 */
	private PaymentProcessResponse processPayment(String memberNo, PaymentProcessRequest request) {
		Booking booking = getBooking(request.getBookingId());
		Payment payment = createAndSavePayment(request, memberNo, booking);

		validatePaymentApprovalConditions(payment, booking);
		executePaymentApproval(payment, booking);
		completePaymentProcess(request, payment, booking);

		return PaymentProcessResponse.from(payment);
	}

	private Booking getBooking(Long bookingId) {
		return bookingRepository.findById(bookingId)
			.orElseThrow(() -> new BusinessException(PaymentError.BOOKING_NOT_FOUND));
	}

	private Payment createAndSavePayment(PaymentProcessRequest request, String memberNo, Booking booking) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		validateBooking(member, booking);
		validatePaymentProcessRequest(request, booking);

		String paymentKey = paymentKeyGenerator.generatePaymentKey(memberNo);
		PaymentInfo paymentInfo = new PaymentInfo(request.getAmount(), request.getPaymentMethod(), PaymentStatus.PENDING);
		Payment payment = Payment.create(member, booking, paymentKey, paymentInfo);

		return paymentRepository.save(payment);
	}

	private void validateBooking(Member member, Booking booking) {
		// 예약 소유자 검증
		if (!booking.getMember().getId().equals(member.getId())) {
			throw new BusinessException(PaymentError.BOOKING_ACCESS_DENIED);
		}

		// 예약 상태 검증 (결제 가능한 상태인지)
//		if (!booking.canBePaid()) {
//			throw new BusinessException(PaymentError.BOOKING_NOT_PAYABLE);
//		}
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

	private void markBookingAsPaid(Booking booking) {
//		booking.approve();

		log.info("예약 결제 완료 처리: bookingId={}", booking.getId());
	}

	private void completePaymentProcess(PaymentProcessRequest request, Payment payment, Booking booking) {
		// 티켓 발급
		generateTicket(booking);

		log.info("결제 완료: paymentKey={}, bookingId={}, amount={}",
			payment.getPaymentKey(), request.getBookingId(), request.getAmount());
	}

	private void generateTicket(Booking booking) {
		seatBookingQueryRepository.findSeatInfoByBookingId(booking.getId())
			.forEach(seatInfoProjection -> ticketService.createTicket(
				booking, seatInfoProjection.getSeat(), seatInfoProjection.getPassengerType()));
	}

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

	private void markBookingAsCancelled(Booking booking) {
		booking.cancel();
		bookingFacade.cancelBooking(booking);

		log.info("예약 취소 처리: bookingId={}", booking.getId());
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

		markBookingAsRefunded(booking);

		log.info("환불 처리 완료: paymentKey={}", payment.getPaymentKey());
	}

	private void markBookingAsRefunded(Booking booking) {
//		booking.refund();

		log.info("예약 환불 처리: bookingId={}", booking.getId());
	}
}
