package com.sudo.raillo.train.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.train.domain.TrainScheduleTemplate;

public interface TrainScheduleTemplateRepository extends JpaRepository<TrainScheduleTemplate, UUID> {

	@Query("SELECT ts FROM TrainScheduleTemplate ts JOIN FETCH ts.scheduleStops")
	List<TrainScheduleTemplate> findAllWithScheduleStops();
}
