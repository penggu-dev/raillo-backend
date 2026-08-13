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

	/**
	 * 해당 스케줄에서 아직 아무도 점유하지 않은 좌석 조회
	 *
	 * <p>SeatOccupancy는 만료 여부를 따지지 않고 행이 하나라도 있으면 제외한다.
	 * 만료된 행도 유니크 인덱스는 그대로 점유하고 있어, 그 좌석을 고르면 픽스처 생성이 실패한다.</p>
	 */
	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar tc JOIN FETCH tc.train t " +
		"WHERE t.id = :trainId AND tc.carType = :carType " +
		"AND s.id NOT IN (SELECT sb.seat.id FROM SeatBooking sb WHERE sb.trainSchedule.id = :trainScheduleId) " +
		"AND s.id NOT IN (SELECT so.seat.id FROM SeatOccupancy so WHERE so.trainSchedule.id = :trainScheduleId)")
	List<Seat> findAvailableSeatsByTrainIdAndCarType(Long trainId, Long trainScheduleId, CarType carType, Pageable pageable);

	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar tc JOIN FETCH tc.train t WHERE t.id = :trainId")
	List<Seat> findByTrainIdWithTrainCar(Long trainId);
}
