package com.sudo.raillo.batch.infrastructure.jdbc;

import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;

public interface ScheduleStopJdbcRepository {

	void saveAll(List<ScheduleStop> scheduleStops);
}
