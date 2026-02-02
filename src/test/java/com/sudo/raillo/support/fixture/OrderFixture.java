package com.sudo.raillo.support.fixture;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderFixture {

	private Member member;
	private BigDecimal totalAmount = BigDecimal.valueOf(10000);
	private List<String> pendingBookingIds = new ArrayList<>();

	public static Order create(Member member) {
		return builder()
			.withMember(member)
			.build();
	}

	// builder method
	public static OrderFixture builder() {
		return new OrderFixture();
	}

	public Order build() {
		return Order.create(member, totalAmount, pendingBookingIds);
	}

	public OrderFixture withMember(Member member) {
		this.member = member;
		return this;
	}

	public OrderFixture withTotalAmount(BigDecimal totalAmount) {
		this.totalAmount = totalAmount;
		return this;
	}

	public OrderFixture withPendingBookingIds(List<String> pendingBookingIds) {
		this.pendingBookingIds = pendingBookingIds;
		return this;
	}
}
