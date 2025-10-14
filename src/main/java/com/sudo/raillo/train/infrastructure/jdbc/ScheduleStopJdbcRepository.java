package com.sudo.raillo.train.infrastructure.jdbc;

import java.util.List;

import com.sudo.raillo.train.domain.ScheduleStop;

public interface ScheduleStopJdbcRepository {

	void saveAll(List<ScheduleStop> scheduleStops);
}
