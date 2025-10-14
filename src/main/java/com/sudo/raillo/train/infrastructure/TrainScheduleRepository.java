package com.sudo.raillo.train.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sudo.raillo.train.domain.TrainSchedule;

public interface TrainScheduleRepository extends JpaRepository<TrainSchedule, Long> {

	@Query("SELECT MAX(ts.operationDate) FROM TrainSchedule ts")
	Optional<LocalDate> findLastOperationDate();

	@Query("SELECT ts.operationDate FROM TrainSchedule ts WHERE ts.operationDate IN :dates")
	Set<LocalDate> findExistingOperationDatesIn(Collection<LocalDate> dates);

	List<TrainSchedule> findByScheduleNameInAndOperationDateIn(
		Collection<String> scheduleNames,
		Collection<LocalDate> operationDate
	);
}
