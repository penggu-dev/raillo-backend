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

	/**
	 * 회원의 유효한(점유중이며 만료되지 않은) 예약 목록 조회
	 *
	 * <p>Redis TTL 만료를 {@code expiresAt} 필터로 대체한다.</p>
	 */
	@Query("SELECT r FROM Reservation r "
		+ "JOIN FETCH r.trainSchedule ts JOIN FETCH ts.train "
		+ "JOIN FETCH r.departureStop ds JOIN FETCH ds.station "
		+ "JOIN FETCH r.arrivalStop ast JOIN FETCH ast.station "
		+ "WHERE r.memberNo = :memberNo AND r.status = :status AND r.expiresAt > :now")
	List<Reservation> findActiveByMemberNo(String memberNo, ReservationStatus status, LocalDateTime now);

	/**
	 * 결제 승인 시 예약 행을 잠그고 조회한다.
	 *
	 * <p>만료 정리·중복 결제와 직렬화하기 위한 보조 장치다. 실제 안전장치는
	 * {@code SeatOccupancyRepository.confirmByReservationIds} 의 affected rows 검사다.</p>
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Reservation r WHERE r.reservationCode IN :reservationCodes")
	List<Reservation> findAllByReservationCodeInForUpdate(List<String> reservationCodes);
}
