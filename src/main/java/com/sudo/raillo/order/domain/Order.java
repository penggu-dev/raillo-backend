package com.sudo.raillo.order.domain;

import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.util.OrderCodeGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "order_id")
	@Comment("주문 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("멤버 ID")
	private Member member;

	@Column(nullable = false)
	@Comment("주문번호 (토스 페이먼츠 orderId)")
	private String orderCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("주문 상태")
	private OrderStatus orderStatus;

	@Column(nullable = false)
	@Comment("총 주문 금액")
	private BigDecimal totalAmount;

	@Comment("주문 만료 시간")
	private LocalDateTime expiredAt;

	public static Order create(Member member, BigDecimal totalAmount) {
		Order order = new Order();
		order.member = member;
		order.orderCode = OrderCodeGenerator.generate();
		order.orderStatus = OrderStatus.PENDING;
		validateTotalAmount(totalAmount);
		order.totalAmount = totalAmount;
		return order;
	}

	public void completePayment() {
		validateIsPending();
		this.orderStatus = OrderStatus.ORDERED;
	}

	public void expired() {
		validateIsPending();
		this.orderStatus = OrderStatus.EXPIRED;
		this.expiredAt = LocalDateTime.now();
	}

	private static void validateTotalAmount(BigDecimal totalAmount) {
		if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
			throw new DomainException(OrderError.INVALID_TOTAL_AMOUNT);
		}
	}

	private void validateIsPending() {
		if (this.orderStatus != OrderStatus.PENDING) {
			throw new DomainException(OrderError.NOT_PENDING);
		}
	}
}
