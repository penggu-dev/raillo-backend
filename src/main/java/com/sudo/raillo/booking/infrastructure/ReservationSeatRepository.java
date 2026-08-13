package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.booking.domain.ReservationSeat;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

	@Query("SELECT rs FROM ReservationSeat rs "
		+ "JOIN FETCH rs.seat s JOIN FETCH s.trainCar "
		+ "WHERE rs.reservation.id IN :reservationIds")
	List<ReservationSeat> findAllByReservationIdInWithSeat(List<Long> reservationIds);

	List<ReservationSeat> findAllByReservationId(Long reservationId);

	int countByReservationId(Long reservationId);
}
