package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.domain.SeatBooking;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatBookingRepository extends JpaRepository<SeatBooking, Long> {

	List<SeatBooking> findByBookingId(Long bookingId);

	void deleteAllByBookingId(Long bookingId);

	@Query("SELECT sb FROM SeatBooking sb WHERE sb.trainSchedule.id = :trainScheduleId " +
		"AND sb.seat.id IN :seatIds " +
		"AND sb.departureStopOrder < :arrivalStopOrder " +
		"AND sb.arrivalStopOrder > :departureStopOrder")
	List<SeatBooking> findOverlappingSeatBookings(
		Long trainScheduleId,
		List<Long> seatIds,
		int departureStopOrder,
		int arrivalStopOrder
	);
}
