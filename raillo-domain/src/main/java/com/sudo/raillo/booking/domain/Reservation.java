package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 예약(임시, 결제 전). Redis에 JSON으로 저장되며 TTL이 지나면 사라진다.
 *
 * @param departureAt 출발 정차역의 출발 일시. 자정을 넘겨 운행하는 열차는 운행일 다음 날
 * @param expiresAt 예약 만료 일시. Redis TTL과 같은 시각
 */
public record Reservation(
	String reservationId,
	String memberNo,
	long trainScheduleId,
	int trainNumber,
	String trainName,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
	LocalDate operationDate,

	ReservationStop departure,
	ReservationStop arrival,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
	LocalDateTime departureAt,

	CarType carType,
	List<ReservationSeat> seats,
	BigDecimal totalFare,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
	LocalDateTime createdAt,

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
	LocalDateTime expiresAt
) {

	public Reservation {
		seats = List.copyOf(seats);
	}

	public static Reservation create(
		String reservationId,
		String memberNo,
		long trainScheduleId,
		int trainNumber,
		String trainName,
		LocalDate operationDate,
		ReservationStop departure,
		ReservationStop arrival,
		LocalDateTime departureAt,
		CarType carType,
		List<ReservationSeat> seats,
		LocalDateTime createdAt,
		Duration ttl
	) {
		BigDecimal totalFare = seats.stream()
			.map(ReservationSeat::fare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		return new Reservation(
			reservationId, memberNo, trainScheduleId, trainNumber, trainName, operationDate,
			departure, arrival, departureAt, carType, seats, totalFare, createdAt, createdAt.plus(ttl)
		);
	}

	@JsonIgnore
	public List<Long> getSeatIds() {
		return seats.stream().map(ReservationSeat::seatId).toList();
	}

	@JsonIgnore
	public List<Long> getTrainCarIds() {
		return seats.stream().map(ReservationSeat::trainCarId).distinct().toList();
	}
}
