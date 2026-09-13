package com.sudo.raillo.batch.infrastructure.jdbc;

import com.sudo.raillo.train.domain.TrainSchedule;
import java.util.List;

public interface TrainScheduleJdbcRepository {

	void saveAll(List<TrainSchedule> trainSchedules);
}
