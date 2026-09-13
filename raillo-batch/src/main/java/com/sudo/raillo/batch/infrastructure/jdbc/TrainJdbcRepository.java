package com.sudo.raillo.batch.infrastructure.jdbc;

import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import java.util.List;

public interface TrainJdbcRepository {

	void saveAllTrains(List<Train> trains);

	void saveAllTrainCars(List<TrainCar> trainCars);

	void saveAllSeats(List<Seat> seats);
}
