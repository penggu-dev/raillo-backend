package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sudo.raillo.booking.domain.SeatBooking;

import jakarta.persistence.LockModeType;

public interface SeatBookingRepository extends JpaRepository<SeatBooking, Long> {

	List<SeatBooking> findByBookingId(Long bookingId);

	void deleteAllByBookingId(Long bookingId);

	@Query("SELECT sb FROM SeatBooking sb WHERE sb.trainSchedule.id = :trainScheduleId AND sb.seat.id IN :seatIds")
	List<SeatBooking> findByTrainScheduleIdAndSeatIds(
		@Param("trainScheduleId") Long trainScheduleId,
		@Param("seatIds") List<Long> seatId
	);
}
