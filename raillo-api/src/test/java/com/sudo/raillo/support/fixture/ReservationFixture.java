package com.sudo.raillo.support.fixture;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.ReservationStop;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationFixture {

	private String reservationId = "RV20260917120000ABC123";
	private String memberNo = "202507300001";
	private long trainScheduleId = 1L;
	private int trainNumber = 1;
	private String trainName = "KTX";
	private LocalDate operationDate = LocalDate.now().plusDays(1);
	private ReservationStop departure = new ReservationStop(1L, 0, 1L, "서울", LocalTime.of(5, 0));
	private ReservationStop arrival = new ReservationStop(2L, 1, 2L, "부산", LocalTime.of(8, 0));
	private CarType carType = CarType.STANDARD;
	private List<ReservationSeat> seats = List.of(
		new ReservationSeat(1L, 1L, 1, "1A", PassengerType.ADULT, BigDecimal.valueOf(50000)));
	private LocalDateTime createdAt = LocalDateTime.now();
	private Duration ttl = Duration.ofMinutes(10);

	public static Reservation create() {
		return builder().build();
	}

	public static ReservationFixture builder() {
		return new ReservationFixture();
	}

	public Reservation build() {
		return Reservation.create(
			reservationId, memberNo, trainScheduleId, trainNumber, trainName, operationDate,
			departure, arrival, LocalDateTime.of(operationDate, departure.time()),
			carType, seats, createdAt, ttl
		);
	}

	public ReservationFixture withReservationId(String reservationId) {
		this.reservationId = reservationId;
		return this;
	}

	public ReservationFixture withMemberNo(String memberNo) {
		this.memberNo = memberNo;
		return this;
	}

	public ReservationFixture withTrainScheduleId(long trainScheduleId) {
		this.trainScheduleId = trainScheduleId;
		return this;
	}

	public ReservationFixture withOperationDate(LocalDate operationDate) {
		this.operationDate = operationDate;
		return this;
	}

	public ReservationFixture withDeparture(ReservationStop departure) {
		this.departure = departure;
		return this;
	}

	public ReservationFixture withArrival(ReservationStop arrival) {
		this.arrival = arrival;
		return this;
	}

	public ReservationFixture withCarType(CarType carType) {
		this.carType = carType;
		return this;
	}

	public ReservationFixture withSeats(List<ReservationSeat> seats) {
		this.seats = seats;
		return this;
	}

	/** 좌석 ID와 객차 ID만 지정하는 간편 메서드. 라벨·운임은 기본값이다. */
	public ReservationFixture withSeatIds(long trainCarId, Long... seatIds) {
		this.seats = java.util.Arrays.stream(seatIds)
			.map(seatId -> new ReservationSeat(seatId, trainCarId, 1, "1A", PassengerType.ADULT, BigDecimal.valueOf(50000)))
			.toList();
		return this;
	}

	public ReservationFixture withCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
		return this;
	}

	public ReservationFixture withTtl(Duration ttl) {
		this.ttl = ttl;
		return this;
	}
}
