package com.sudo.raillo.payment.application.validator;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentValidator {

	private final PaymentRepository paymentRepository;

	/**
	 * 결제 소유자 검증
	 */
	public void validatePaymentOwner(Payment payment, Member member) {
		if (!payment.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Payment의 소유자가 아님: paymentId={}, requestMemberId={}, paymentMemberId={}",
				payment.getId(), member.getId(), payment.getMember().getId());
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

	/**
	 * 클라이언트 요청 금액, Order 금액, Payment 금액 모두 일치 여부 검증
	 * 1. 클라이언트 요청 금액 vs Order 금액
	 * 2. Order 금액 vs Payment 금액
	 */
	public void validateAmounts(BigDecimal requestAmount, BigDecimal orderAmount, BigDecimal paymentAmount) {
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
	public void validateTossResponseMatchesRequest(TossPaymentConfirmResponse tossResponse, PaymentConfirmRequest request) {
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
}
