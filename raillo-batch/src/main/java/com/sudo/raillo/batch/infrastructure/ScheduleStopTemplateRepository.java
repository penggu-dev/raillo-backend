package com.sudo.raillo.batch.infrastructure;

import com.sudo.raillo.train.domain.ScheduleStopTemplate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleStopTemplateRepository extends JpaRepository<ScheduleStopTemplate, UUID> {
}
