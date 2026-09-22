package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment 애그리게이트 상태 전이 오케스트레이션.
 *
 * <p>결제 준비/승인 유스케이스가 공유하는 내부 도메인 서비스. provided port로 노출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentModifier {

	private final PaymentRepository paymentRepository;

	public Payment createPayment(Member member, Order order) {
		Payment payment = Payment.create(member, order);
		Payment saved = paymentRepository.save(payment);
		log.info("[결제 생성] paymentId={}, orderId={}, amount={}", saved.getId(), order.getId(), order.getTotalAmount());
		return saved;
	}
}
