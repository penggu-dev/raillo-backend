package com.sudo.raillo.support.fixture;

import java.math.BigDecimal;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentFixture {

	private Member member;
	private Order order;
	private BigDecimal amount = BigDecimal.valueOf(10000);

	public static Payment create(Member member, Order order) {
		return builder()
			.withMember(member)
			.withOrder(order)
			.build();
	}

	// builder method
	public static PaymentFixture builder() {
		return new PaymentFixture();
	}

	public Payment build() {
		return Payment.create(member, order, amount);
	}

	public PaymentFixture withMember(Member member) {
		this.member = member;
		return this;
	}

	public PaymentFixture withOrder(Order order) {
		this.order = order;
		return this;
	}

	public PaymentFixture withAmount(BigDecimal amount) {
		this.amount = amount;
		return this;
	}
}
