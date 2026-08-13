package com.sudo.raillo.booking.infrastructure;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatOccupancy;
import com.sudo.raillo.booking.domain.status.SeatOccupancyStatus;

public interface SeatOccupancyRepository extends JpaRepository<SeatOccupancy, Long> {

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM SeatOccupancy o "
		+ "WHERE o.trainSchedule.id = :trainScheduleId "
		+ "AND o.seat.id IN :seatIds "
		+ "AND o.sectionOrder BETWEEN :fromSectionOrder AND :toSectionOrder "
		+ "AND o.expiresAt <= :now")
	int deleteExpiredInSections(
		@Param("trainScheduleId") Long trainScheduleId,
		@Param("seatIds") List<Long> seatIds,
		@Param("fromSectionOrder") int fromSectionOrder,
		@Param("toSectionOrder") int toSectionOrder,
		@Param("now") LocalDateTime now
	);

	@Modifying(flushAutomatically = true)
	@Query("UPDATE SeatOccupancy o "
		+ "SET o.status = :confirmedStatus, o.booking = :booking, o.expiresAt = :neverExpires "
		+ "WHERE o.reservation.id = :reservationId "
		+ "AND o.status = :heldStatus "
		+ "AND o.expiresAt > :now")
	int confirmByReservationId(
		@Param("reservationId") Long reservationId,
		@Param("booking") Booking booking,
		@Param("heldStatus") SeatOccupancyStatus heldStatus,
		@Param("confirmedStatus") SeatOccupancyStatus confirmedStatus,
		@Param("neverExpires") LocalDateTime neverExpires,
		@Param("now") LocalDateTime now
	);

	List<SeatOccupancy> findAllByReservationId(Long reservationId);

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM SeatOccupancy o WHERE o.reservation.id IN :reservationIds")
	int deleteByReservationIdIn(@Param("reservationIds") List<Long> reservationIds);

	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM SeatOccupancy o WHERE o.booking.id = :bookingId")
	int deleteByBookingId(@Param("bookingId") Long bookingId);

	/**
	 * 특정 열차의 좌석들에 대한 점유를 모두 삭제한다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM SeatOccupancy o "
		+ "WHERE o.trainSchedule.id = :trainScheduleId AND o.seat.id IN :seatIds")
	int deleteByTrainScheduleIdAndSeatIdIn(
		@Param("trainScheduleId") Long trainScheduleId,
		@Param("seatIds") List<Long> seatIds
	);
}
