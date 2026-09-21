package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentModifierTest {

	@Autowired
	private PaymentModifier paymentModifier;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	private Member member;
	private Order order;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		order = orderRepository.save(
			OrderFixture.builder()
				.withMember(member)
				.withTotalAmount(BigDecimal.valueOf(50000))
				.build()
		);
	}

	@Test
	@DisplayName("Payment 생성 시 PENDING 상태로 저장된다")
	void createPayment_success() {
		// when
		Payment payment = paymentModifier.createPayment(member, order);

		// then
		assertThat(payment.getId()).isNotNull();
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getMember().getId()).isEqualTo(member.getId());
		assertThat(payment.getOrder().getId()).isEqualTo(order.getId());
		assertThat(payment.getAmount()).isEqualByComparingTo(order.getTotalAmount());
		assertThat(payment.getOrderCode()).isEqualTo(order.getOrderCode());
	}

	// Payment.fail 호출 삭제(옵션 Y): PaymentModifier.failPaymentInNewTransaction이 제거되어
	// 관련 실패 처리 테스트는 삭제. 실패는 PaymentAttempt.markFailed로만 관리한다.
}
