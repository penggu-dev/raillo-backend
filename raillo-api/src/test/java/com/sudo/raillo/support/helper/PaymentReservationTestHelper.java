package com.sudo.raillo.support.helper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.sudo.raillo.booking.domain.*;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.infrastructure.*;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 결제 테스트에서 실제 DB 기준정보로 Reservation snapshot을 조립한다. */
@Component
@RequiredArgsConstructor
public class PaymentReservationTestHelper {
	private final TrainScheduleRepository schedules;
	private final ScheduleStopRepository stops;
	private final SeatRepository seats;
	private final FareCalculator fares;
	private final ReservationService reservationService;
	private final ReservationRedisRepository repository;
	private final StringRedisTemplate redis;
	
	public record SeatPassenger(Long seatId, PassengerType passengerType) {}
	public Builder builder() { return new Builder(); }
	public void save(Reservation reservation) { reservationService.reserve(reservation, Duration.ofMinutes(10)); }
	public Optional<Reservation> find(Reservation reservation) {
		return repository.find(reservation.trainScheduleId(), reservation.reservationId());
	}
	public void delete(Reservation reservation) {
		redis.delete(ReservationCacheKey.reservation(reservation.trainScheduleId(), reservation.reservationId()));
	}

	public Reservation build(Builder b) {
		var schedule = schedules.findAllByIdWithTrain(List.of(b.scheduleId)).getFirst();
		var dep = stops.findAllByIdWithStation(List.of(b.departureId)).getFirst();
		var arr = stops.findAllByIdWithStation(List.of(b.arrivalId)).getFirst();
		var snapshotSeats = b.passengers.stream().map(p -> {
			var seat = seats.findAllByIdWithTrainCar(List.of(p.seatId())).getFirst();
			var car = seat.getTrainCar();
			var fare = fares.calculateFare(dep.getStation().getId(), arr.getStation().getId(), p.passengerType(), car.getCarType());
			if (b.totalFare != null) {
				fare = b.totalFare.divide(BigDecimal.valueOf(b.passengers.size()), 2, java.math.RoundingMode.HALF_UP);
			}
			return new ReservationSeat(seat.getId(), car.getId(), car.getCarNumber(), "1A", p.passengerType(), fare);
		}).toList();
		return Reservation.create(b.id, b.memberNo, b.scheduleId, 1, "KTX", schedule.getOperationDate(),
			new ReservationStop(dep.getId(), dep.getStopOrder(), dep.getStation().getId(), "출발", java.time.LocalTime.of(5, 0)),
			new ReservationStop(arr.getId(), arr.getStopOrder(), arr.getStation().getId(), "도착", java.time.LocalTime.of(8, 0)),
			LocalDateTime.now().plusDays(1), com.sudo.raillo.train.domain.type.CarType.STANDARD,
			snapshotSeats, LocalDateTime.now(), Duration.ofMinutes(10));
	}

	public class Builder {
		private BigDecimal totalFare;
		private String id = "RV" + UUID.randomUUID().toString().replace("-", "");
		private String memberNo = "202601010001";
		private Long scheduleId = 1L, departureId = 1L, arrivalId = 2L;
		private List<SeatPassenger> passengers = List.of(new SeatPassenger(1L, PassengerType.ADULT));
		public Builder withId(String v) { id=v; return this; }
		public Builder withMemberNo(String v) { memberNo=v; return this; }
		public Builder withTrainScheduleId(Long v) { scheduleId=v; return this; }
		public Builder withDepartureStopId(Long v) { departureId=v; return this; }
		public Builder withArrivalStopId(Long v) { arrivalId=v; return this; }
		public Builder withSeats(List<SeatPassenger> v) { passengers=v; return this; }
		public Builder withTotalFare(BigDecimal v) { totalFare=v; return this; }
		public Reservation build() { return PaymentReservationTestHelper.this.build(this); }
	}
}
