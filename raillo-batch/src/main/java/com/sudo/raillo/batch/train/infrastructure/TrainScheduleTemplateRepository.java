package com.sudo.raillo.batch.train.infrastructure;

import com.sudo.raillo.train.domain.TrainScheduleTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TrainScheduleTemplateRepository extends JpaRepository<TrainScheduleTemplate, UUID> {

	@Query("SELECT ts FROM TrainScheduleTemplate ts JOIN FETCH ts.scheduleStops")
	List<TrainScheduleTemplate> findAllWithScheduleStops();
}
