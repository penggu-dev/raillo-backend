package com.sudo.raillo.train.infrastructure;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;

public interface TrainCarRepository extends JpaRepository<TrainCar, Long> {

	List<TrainCar> findByTrainIn(Collection<Train> trains);

	List<TrainCar> findAllByTrainId(Long trainId);

	@Query("SELECT tc.id FROM TrainSchedule ts JOIN ts.train t JOIN TrainCar tc ON tc.train.id = t.id "
		+ "WHERE ts.id = :trainScheduleId AND tc.carType = :carType")
	List<Long> findIdsByScheduleIdAndCarType(Long trainScheduleId, CarType carType);
}
