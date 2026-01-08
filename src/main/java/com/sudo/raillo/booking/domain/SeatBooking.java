package com.sudo.raillo.booking.domain;

import com.sudo.raillo.train.domain.ScheduleStop;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "seat_booking",
	indexes = {@Index(name = "idx_seat_booking_seat", columnList = "train_schedule_id, seat_id")},
	uniqueConstraints = {@UniqueConstraint(columnNames = {"train_schedule_id", "seat_id", "booking_id"})}
)
public class SeatBooking extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "seat_booking_id")
	@Comment("예약 상태 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_schedule_id", nullable = false)
	@Comment("운행 일정 ID")
	private TrainSchedule trainSchedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seat_id", nullable = false)
	@Comment("좌석 ID")
	private Seat seat;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예약 ID")
	private Booking booking;

	@Enumerated(EnumType.STRING)
	@Column(name = "passenger_type", nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "departure_stop_id", nullable = false)
	@Comment("출발 정류장 ID")
	private ScheduleStop departureStop;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "arrival_stop_id", nullable = false)
	@Comment("도착 정류장 ID")
	private ScheduleStop arrivalStop;

	public static SeatBooking create(
		Booking booking,
		Seat seat,
		PassengerType passengerType
	) {
		SeatBooking seatBooking = new SeatBooking();
		seatBooking.booking = booking;
		seatBooking.seat = seat;
		seatBooking.passengerType = passengerType;
		seatBooking.trainSchedule = booking.getTrainSchedule();
		seatBooking.departureStop = booking.getDepartureStop();
		seatBooking.arrivalStop = booking.getArrivalStop();
		return seatBooking;
	}
}
