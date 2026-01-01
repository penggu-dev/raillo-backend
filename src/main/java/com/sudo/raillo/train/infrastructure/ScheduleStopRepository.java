package com.sudo.raillo.train.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.train.domain.ScheduleStop;

@Repository
public interface ScheduleStopRepository extends JpaRepository<ScheduleStop, Long> {

	Optional<ScheduleStop> findByTrainScheduleIdAndStationId(Long trainScheduleId, Long id);

	@Query("SELECT ss FROM ScheduleStop ss JOIN FETCH ss.station WHERE ss.id IN :stopIds")
	List<ScheduleStop> findAllByIdWithStation(@Param("stopIds") Collection<Long> stopIds);
}
