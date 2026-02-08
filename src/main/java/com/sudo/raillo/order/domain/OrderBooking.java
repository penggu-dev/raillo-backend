package com.sudo.raillo.order.domain;

import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class OrderBooking extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "order_booking_id")
	@Comment("주문 예약 ID")
	private Long id;

	@Column(name = "pending_booking_id", nullable = false)
	@Comment("예약 ID")
	private String pendingBookingId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("주문 ID")
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_schedule_id", nullable = false)
	@Comment("운행 일정 ID")
	private TrainSchedule trainSchedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "departure_stop_id", nullable = false)
	@Comment("출발 정류장 ID")
	private ScheduleStop departureStop;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "arrival_stop_id", nullable = false)
	@Comment("도착 정류장 ID")
	private ScheduleStop arrivalStop;

	@Column(nullable = false)
	@Comment("총 운임")
	private BigDecimal totalFare;

	public static OrderBooking create(
		String pendingBookingId,
		Order order,
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		BigDecimal totalFare
	) {
		OrderBooking orderBooking = new OrderBooking();
		orderBooking.pendingBookingId = pendingBookingId;
		orderBooking.order = order;
		orderBooking.trainSchedule = trainSchedule;
		orderBooking.departureStop = departureStop;
		orderBooking.arrivalStop = arrivalStop;
		orderBooking.totalFare = totalFare;
		return orderBooking;
	}
}
