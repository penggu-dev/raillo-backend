package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	indexes = {
		@Index(name = "idx_reservation_seat_reservation", columnList = "reservation_id")
	}
)
public class ReservationSeat extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "reservation_seat_id")
	@Comment("예약 좌석 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reservation_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예약 ID")
	private Reservation reservation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seat_id", nullable = false)
	@Comment("좌석 ID")
	private Seat seat;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	@Column(nullable = false)
	@Comment("좌석 운임")
	private BigDecimal fare;

	public static ReservationSeat create(
		Reservation reservation,
		Seat seat,
		PassengerType passengerType,
		BigDecimal fare
	) {
		ReservationSeat reservationSeat = new ReservationSeat();
		reservationSeat.reservation = reservation;
		reservationSeat.seat = seat;
		reservationSeat.passengerType = passengerType;
		reservationSeat.fare = fare;
		return reservationSeat;
	}
}
