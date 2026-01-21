package com.sudo.raillo.global.event.infrastructure;

import com.sudo.raillo.global.event.domain.Event;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Event Store
 */
@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

	@Query(value = """
		SELECT *
		FROM event e
		WHERE e.status IN ('PROGRESS', 'RETRY')
		  AND e.retry_count < 3
		ORDER BY e.created_at ASC
		LIMIT :limit
		FOR UPDATE SKIP LOCKED
		""", nativeQuery = true)
	List<Event> findPendingEvents(@Param("limit") int limit);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT e FROM Event e WHERE e.id = :id")
	Optional<Event> findByIdWithLock(Long id);

	@Modifying
	@Query("UPDATE Event e SET e.status = 'RETRY', e.retryCount = e.retryCount + 1 WHERE e.id IN :ids")
	void updateStatusToRetryAndIncrementCount(@Param("ids") List<Long> ids);

	@Modifying
	@Query("UPDATE Event e SET e.status = 'FAILED' WHERE e.id IN :ids")
	void updateStatusToFailed(@Param("ids") List<Long> ids);
}
