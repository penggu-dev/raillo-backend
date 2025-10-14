package com.sudo.raillo.train.infrastructure.jdbc;

import java.util.List;

import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;

public interface TrainJdbcRepository {

	void saveAllTrains(List<Train> trains);

	void saveAllTrainCars(List<TrainCar> trainCars);

	void saveAllSeats(List<Seat> seats);
}
