package com.sudo.raillo.global.event.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.global.event.domain.OutboxEvent;

import jakarta.persistence.LockModeType;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE) // 비관적 락 적용
	@Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' AND e.retryCount < 3 ORDER BY e.createdAt ASC LIMIT :limit")
	List<OutboxEvent> findPendingEventsWithLock(@Param("limit") int limit);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT e FROM OutboxEvent e WHERE e.id = :id")
	Optional<OutboxEvent> findByIdWithLock(@Param("id") Long id);

	// 완료 이벤트 정리용 (나중에 배치 처리로 삭제 필요)
	@Modifying
	@Query("DELETE FROM OutboxEvent e WHERE e.status = 'COMPLETED' AND e.createdAt < :before")
	int deleteCompletedEvents(@Param("before") LocalDateTime before);
}
