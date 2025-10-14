package com.sudo.raillo.train.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.train.domain.ScheduleStop;

@Repository
public interface ScheduleStopRepository extends JpaRepository<ScheduleStop, Long> {

	Optional<ScheduleStop> findByTrainScheduleIdAndStationId(Long trainScheduleId, Long id);
}
