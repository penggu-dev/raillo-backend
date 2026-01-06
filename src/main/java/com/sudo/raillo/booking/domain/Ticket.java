package com.sudo.raillo.booking.domain;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ticket_id")
	@Comment("승차권 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seat_id")
	@OnDelete(action = OnDeleteAction.SET_NULL)
	@Comment("좌석 ID")
	private Seat seat;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예약 ID")
	private Booking booking;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("승차권 상태")
	private TicketStatus ticketStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	@Comment("승차권 번호")
	private String ticketNumber;

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
