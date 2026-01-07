package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

	private final PaymentRepository paymentRepository;

	/**
	 * Payment 생성 (PENDING 상태)
	 */
	public Payment createPayment(Member member, Order order, BigDecimal amount) {
		Payment payment = Payment.create(member, order, amount);
		Payment savedPayment = paymentRepository.save(payment);

		log.info("[결제 생성] paymentId={}, orderId={}, amount={}", savedPayment.getId(), order.getId(), amount);

		return savedPayment;
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
	@Transactional(readOnly = true)
	public Payment getPaymentByOrder(Order order) {
		return paymentRepository.findByOrder(order)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}

	/**
	 * PaymentKey 업데이트 (별도 트랜잭션 - 무조건 커밋)
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updatePaymentKeyInNewTransaction(Long paymentId, String paymentKey) {
		// 새로운 트랜잭션의 영속성 컨텍스트에서 다시 조회
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		// → 이제 이 payment는 새로운 영속성 컨텍스트에 속함
		payment.updatePaymentKey(paymentKey);
	}

	/**
	 * Payment 실패 처리 (별도 트랜잭션 - 무조건 커밋)
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failPaymentInNewTransaction(Long paymentId, String failureCode, String failureMessage) {
		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));

		payment.fail(failureCode, failureMessage);
	}
}
