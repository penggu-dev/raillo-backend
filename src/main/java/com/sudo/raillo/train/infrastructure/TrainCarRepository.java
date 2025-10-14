package com.sudo.raillo.train.infrastructure;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;

public interface TrainCarRepository extends JpaRepository<TrainCar, Long> {

	List<TrainCar> findByTrainIn(Collection<Train> trains);

	List<TrainCar> findAllByTrainId(Long trainId);
}
