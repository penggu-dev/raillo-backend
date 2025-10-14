package com.sudo.raillo.train.infrastructure.jdbc;

import java.util.List;

import com.sudo.raillo.train.domain.TrainSchedule;

public interface TrainScheduleJdbcRepository {

	void saveAll(List<TrainSchedule> trainSchedules);
}
