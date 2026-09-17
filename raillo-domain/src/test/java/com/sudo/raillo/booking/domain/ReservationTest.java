package com.sudo.raillo.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

class ReservationTest {

	@Test
	@DisplayName("예약 생성 시 총 운임은 좌석 운임의 합이고 만료 일시는 생성 일시에 TTL을 더한 값이다")
	void create() {
		// given
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 17, 12, 0);
		List<ReservationSeat> seats = List.of(
			new ReservationSeat(46456L, 231L, 3, "12A", PassengerType.ADULT, new BigDecimal("59800")),
			new ReservationSeat(46457L, 231L, 3, "12B", PassengerType.CHILD, new BigDecimal("35880.0"))
		);

		// when
		Reservation reservation = Reservation.create(
			"RV1", "202601010001", 1001L, 101, "KTX", LocalDate.of(2026, 10, 20),
			new ReservationStop(9001L, 0, 1L, "서울", LocalTime.of(6, 0)),
			new ReservationStop(9004L, 3, 5L, "부산", LocalTime.of(8, 52)),
			LocalDateTime.of(2026, 10, 20, 6, 0),
			CarType.STANDARD, seats, createdAt, Duration.ofMinutes(10)
		);

		// then
		assertThat(reservation.totalFare()).isEqualByComparingTo(new BigDecimal("95680"));
		assertThat(reservation.expiresAt()).isEqualTo(LocalDateTime.of(2026, 9, 17, 12, 10));
		assertThat(reservation.createdAt()).isEqualTo(createdAt);
		assertThat(reservation.getSeatIds()).containsExactly(46456L, 46457L);
		assertThat(reservation.getTrainCarIds()).containsExactly(231L);
	}

	@Test
	@DisplayName("여러 객차의 좌석을 예약하면 객차 ID는 중복 없이 나열된다")
	void distinctTrainCarIds() {
		// given
		List<ReservationSeat> seats = List.of(
			seat(1L, 231L), seat(2L, 231L), seat(3L, 232L)
		);

		// when
		Reservation reservation = createWith(seats);

		// then
		assertThat(reservation.getTrainCarIds()).containsExactly(231L, 232L);
	}

	@Test
	@DisplayName("좌석 목록은 생성 이후 바꿀 수 없다")
	void seatsAreImmutable() {
		// given
		List<ReservationSeat> seats = new ArrayList<>(List.of(seat(1L, 231L)));
		Reservation reservation = createWith(seats);

		// when
		seats.add(seat(2L, 231L));

		// then
		assertThat(reservation.seats()).hasSize(1);
		assertThatThrownBy(() -> reservation.seats().add(seat(3L, 231L)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static ReservationSeat seat(long seatId, long trainCarId) {
		return new ReservationSeat(seatId, trainCarId, 3, "1A", PassengerType.ADULT, new BigDecimal("10000"));
	}

	private static Reservation createWith(List<ReservationSeat> seats) {
		return Reservation.create(
			"RV1", "202601010001", 1001L, 101, "KTX", LocalDate.of(2026, 10, 20),
			new ReservationStop(9001L, 0, 1L, "서울", LocalTime.of(6, 0)),
			new ReservationStop(9004L, 3, 5L, "부산", LocalTime.of(8, 52)),
			LocalDateTime.of(2026, 10, 20, 6, 0),
			CarType.STANDARD, seats, LocalDateTime.of(2026, 9, 17, 12, 0), Duration.ofMinutes(10)
		);
	}
}
