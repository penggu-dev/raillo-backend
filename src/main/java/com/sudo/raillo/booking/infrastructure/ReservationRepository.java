package com.sudo.raillo.booking.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByReservationCode(String reservationCode);

	List<Reservation> findAllByReservationCodeIn(List<String> reservationCodes);

	@Query("SELECT r FROM Reservation r "
		+ "JOIN FETCH r.trainSchedule ts JOIN FETCH ts.train "
		+ "JOIN FETCH r.departureStop ds JOIN FETCH ds.station "
		+ "JOIN FETCH r.arrivalStop ast JOIN FETCH ast.station "
		+ "WHERE r.memberNo = :memberNo AND r.status = :status AND r.expiresAt > :now")
	List<Reservation> findActiveByMemberNo(String memberNo, ReservationStatus status, LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Reservation r WHERE r.reservationCode IN :reservationCodes")
	List<Reservation> findAllByReservationCodeInForUpdate(List<String> reservationCodes);
}
