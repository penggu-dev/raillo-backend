package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.train.domain.Seat;
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
public class Ticket extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ticket_id")
	@Comment("승차권 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예매 ID")
	private Booking booking;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seat_id")
	@OnDelete(action = OnDeleteAction.SET_NULL)
	@Comment("좌석 ID")
	private Seat seat;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("승차권 상태")
	private TicketStatus ticketStatus;

	@Comment("승차권 번호")
	private String ticketNumber;

	@Column(nullable = false)
	@Comment("운임")
	private BigDecimal fare;

	public static Ticket create(
		Booking booking,
		Seat seat,
		PassengerType passengerType,
		BigDecimal fare
	) {
		Ticket ticket = new Ticket();
		ticket.booking = booking;
		ticket.seat = seat;
		ticket.passengerType = passengerType;
		ticket.ticketStatus = TicketStatus.ISSUED;
		ticket.fare = fare;
		return ticket;
	}

	public void cancel() {
		this.ticketStatus = TicketStatus.CANCELLED;
	}

	public void use() {
		this.ticketStatus = TicketStatus.USED;
	}

	public boolean canBeCancelled() {
		return this.ticketStatus.isCancellable();
	}

	public boolean canBeUsed() {
		return this.ticketStatus.isUsable();
	}
}
