package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;
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
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
	@Comment("예매 좌석 ID")
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
	@Comment("예매 ID")
	private Booking booking;

	@Enumerated(EnumType.STRING)
	@Column(name = "passenger_type", nullable = false)
	@Comment("승객 유형")
	private PassengerType passengerType;

	// 역정규화 필드 - 쿼리 성능 최적화용
	@Enumerated(EnumType.STRING)
	@Column(name = "car_type", nullable = false)
	@Comment("객차 타입")
	private CarType carType;

	@Column(name = "departure_station_id", nullable = false)
	@Comment("출발역 ID")
	private Long departureStationId;

	@Column(name = "arrival_station_id", nullable = false)
	@Comment("도착역 ID")
	private Long arrivalStationId;

	@Column(name = "departure_stop_order", nullable = false)
	@Comment("출발 정차 순서")
	private int departureStopOrder;

	@Column(name = "arrival_stop_order", nullable = false)
	@Comment("도착 정차 순서")
	private int arrivalStopOrder;

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
		// 역정규화 필드 설정
		seatBooking.carType = seat.getTrainCar().getCarType();
		seatBooking.departureStationId = booking.getDepartureStop().getStation().getId();
		seatBooking.arrivalStationId = booking.getArrivalStop().getStation().getId();
		seatBooking.departureStopOrder = booking.getDepartureStop().getStopOrder();
		seatBooking.arrivalStopOrder = booking.getArrivalStop().getStopOrder();
		return seatBooking;
	}
}
