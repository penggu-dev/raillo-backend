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

	@Comment("결제 위치 번호 (예: 온라인 (01), ~~역(02...))")
	private String vendorCode;

	@Comment("결제 날짜 (MMdd)")
	private String purchaseDate;

	@Comment("승차권 결제 순번 (10000~)")
	private String purchaseSeq;

	@Comment("결제 고유번호 (숫자 2자리)")
	private String purchaseUid;

}
