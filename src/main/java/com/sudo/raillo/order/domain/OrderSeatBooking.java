package com.sudo.raillo.order.domain;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.domain.BaseEntity;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSeatBooking extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "order_seat_booking_id")
	@Comment("주문 좌석 예약 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_booking_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("주문 예약 ID")
	private OrderBooking orderBooking;

	@Column(name = "seat_id", nullable = false)
	@Comment("좌석 ID")
	private Long seatId;

	@Enumerated(EnumType.STRING)
	@Column(name = "passenger_type", nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	@Column(nullable = false)
	@Comment("좌석별 운임")
	private BigDecimal fare;

	public static OrderSeatBooking create(
		OrderBooking orderBooking,
		Long seatId,
		PassengerType passengerType,
		BigDecimal fare
	) {
		OrderSeatBooking orderSeatBooking = new OrderSeatBooking();
		orderSeatBooking.orderBooking = orderBooking;
		orderSeatBooking.seatId = seatId;
		orderSeatBooking.passengerType = passengerType;
		orderSeatBooking.fare = fare;
		return orderSeatBooking;
	}
}
