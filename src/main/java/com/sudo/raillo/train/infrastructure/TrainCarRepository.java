package com.sudo.raillo.train.infrastructure;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainCarRepository extends JpaRepository<TrainCar, Long> {

	List<TrainCar> findByTrainIn(Collection<Train> trains);

	List<TrainCar> findAllByTrainId(Long trainId);
}
