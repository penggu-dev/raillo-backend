package com.sudo.raillo.support.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * 테스트에서만 사용하는 Seat 조회 Repository
 */
public interface TestSeatRepository extends JpaRepository<Seat, Long> {

	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar tc JOIN FETCH tc.train t " +
		"WHERE t.id = :trainId AND tc.carType = :carType")
	List<Seat> findByTrainIdAndCarTypeWithTrainCarLimited(Long trainId, CarType carType, Pageable pageable);

	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar tc JOIN FETCH tc.train t " +
		"WHERE t.id = :trainId AND tc.carType = :carType " +
		"AND s.id NOT IN (SELECT sb.seat.id FROM SeatBooking sb WHERE sb.trainSchedule.id = :trainScheduleId)")
	List<Seat> findAvailableSeatsByTrainIdAndCarType(Long trainId, Long trainScheduleId, CarType carType, Pageable pageable);

	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar tc JOIN FETCH tc.train t WHERE t.id = :trainId")
	List<Seat> findByTrainIdWithTrainCar(Long trainId);
}
