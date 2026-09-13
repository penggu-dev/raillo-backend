package com.sudo.raillo.train.infrastructure;

import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.type.CarType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, Long> {

	@Query("SELECT DISTINCT tc.carType FROM Seat s JOIN TrainCar tc ON tc = s.trainCar WHERE s.id IN :seatIds")
	List<CarType> findCarTypes(List<Long> seatIds);

	@Query("SELECT s FROM Seat s JOIN FETCH s.trainCar WHERE s.id IN :seatIds")
	List<Seat> findAllByIdWithTrainCar(Collection<Long> seatIds);
}
