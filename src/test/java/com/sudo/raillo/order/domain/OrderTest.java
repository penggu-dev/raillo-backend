package com.sudo.raillo.order.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.support.fixture.MemberFixture;

class OrderTest {

	@Test
	@DisplayName("주문 생성 시 상태가 PENDING이고 주문 코드가 생성된다")
	void create() {
		// given
		Member member = MemberFixture.createStandardMember();
		BigDecimal totalAmount = BigDecimal.valueOf(10000);

		// when
		Order order = Order.create(member, totalAmount);

		// then
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getOrderCode()).isNotNull();
		assertThat(order.getTotalAmount()).isEqualTo(totalAmount);
		assertThat(order.getMember()).isEqualTo(member);
	}

	@Test
	@DisplayName("총 주문 금액이 0으로 주문을 생성할 수 있다")
	void invalidTotalAmountZero() {
		// given
		Member member = MemberFixture.createStandardMember();
		BigDecimal totalAmount = BigDecimal.ZERO;

		// when
		Order order = Order.create(member, totalAmount);

		// then
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getTotalAmount()).isEqualTo(BigDecimal.ZERO);

	}

	@Test
	@DisplayName("음수 금액으로 주문 생성 시 예외가 발생한다")
	void invalidTotalAmountNegative() {
		// given
		Member member = MemberFixture.createStandardMember();
		BigDecimal invalidAmount = BigDecimal.valueOf(-1000);

		// when & then
		assertThatThrownBy(() -> Order.create(member, invalidAmount))
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.INVALID_TOTAL_AMOUNT.getMessage());
	}

	@Test
	@DisplayName("PENDING 상태의 주문을 결제 완료 처리하면 상태가 ORDERED로 변경된다")
	void completePayment() {
		// given
		Member member = MemberFixture.createStandardMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));

		// when
		order.completePayment();

		// then
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ORDERED);
	}

	@Test
	@DisplayName("PENDING 상태가 아닌 주문을 결제 완료 처리하면 예외가 발생한다")
	void completePaymentFail() {
		// given
		Member member = MemberFixture.createStandardMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));

		// when
		order.completePayment();

		// then
		assertThatThrownBy(order::completePayment)
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.NOT_PENDING.getMessage());
	}

	@Test
	@DisplayName("PENDING 상태의 주문을 만료 처리하면 상태가 EXPIRED로 변경되고 만료 시간이 설정된다")
	void expired() {
		// given
		Member member = MemberFixture.createStandardMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));

		// when
		order.expired();

		// then
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(order.getExpiredAt()).isNotNull();
	}

	@Test
	@DisplayName("PENDING 상태가 아닌 주문을 만료 처리하면 예외가 발생한다")
	void expiredFail() {
		// given
		Member member = MemberFixture.createStandardMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));

		// when
		order.completePayment();

		// then
		assertThatThrownBy(order::expired)
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.NOT_PENDING.getMessage());
	}
}
