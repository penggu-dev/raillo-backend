package com.sudo.raillo.batch.train.infrastructure.jdbc;

import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;

public interface ScheduleStopJdbcRepository {

	void saveAll(List<ScheduleStop> scheduleStops);
}
