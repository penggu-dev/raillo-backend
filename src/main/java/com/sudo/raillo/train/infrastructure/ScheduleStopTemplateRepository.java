package com.sudo.raillo.train.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.train.domain.ScheduleStopTemplate;

public interface ScheduleStopTemplateRepository extends JpaRepository<ScheduleStopTemplate, UUID> {
}
