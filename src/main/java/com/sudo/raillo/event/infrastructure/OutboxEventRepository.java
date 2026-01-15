package com.sudo.raillo.event.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sudo.raillo.event.domain.OutboxEvent;
import com.sudo.raillo.event.domain.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	// === 배치 복구용 쿼리 (SKIP LOCKED) ===

	/**
	 * FAILED 이벤트 조회
	 * 인덱스: idx_status_created (status, created_at)
	 */
	@Query(value = """
        SELECT * FROM outbox_event
        WHERE status = 'FAILED'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
	List<OutboxEvent> findFailedEvents(@Param("limit") int limit);

	/**
	 * PENDING 유실 이벤트 조회 (5분 경과)
	 * 인덱스: idx_status_created (status, created_at)
	 */
	@Query(value = """
        SELECT * FROM outbox_event
        WHERE status = 'PENDING'
          AND created_at < :threshold
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
	List<OutboxEvent> findStalePendingEvents(
		@Param("threshold") LocalDateTime threshold,
		@Param("limit") int limit
	);

	/**
	 * PROCESSING 좀비 이벤트 조회 (10분 경과)
	 * 인덱스: idx_status_processing_started (status, processing_started_at)
	 */
	@Query(value = """
        SELECT * FROM outbox_event
        WHERE status = 'PROCESSING'
          AND processing_started_at < :threshold
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
	List<OutboxEvent> findStuckProcessingEvents(
		@Param("threshold") LocalDateTime threshold,
		@Param("limit") int limit
	);

	// === 관리자 조회용 ===

	Page<OutboxEvent> findByStatus(OutboxStatus status, Pageable pageable);

	List<OutboxEvent> findByStatus(OutboxStatus status);

	/**
	 * 상태별 이벤트 수 조회
	 */
	@Query("""
        SELECT e.status as status, COUNT(e) as count
        FROM OutboxEvent e
        GROUP BY e.status
        """)
	List<Object[]> countByStatusRaw();

	/**
	 * 상태별 이벤트 수를 Map으로 변환
	 */
	default Map<OutboxStatus, Long> countByStatus() {
		List<Object[]> results = countByStatusRaw();
		return results.stream()
			.collect(java.util.stream.Collectors.toMap(
				row -> (OutboxStatus) row[0],
				row -> (Long) row[1]
			));
	}

	// === 정리(Cleanup)용 쿼리 ===

	/**
	 * COMPLETED 이벤트 삭제 (batch 단위)
	 */
	@Modifying
	@Query(value = """
        DELETE FROM outbox_event
        WHERE status = 'COMPLETED'
          AND completed_at < :threshold
        LIMIT :batchSize
        """, nativeQuery = true)
	int deleteCompletedBefore(
		@Param("threshold") LocalDateTime threshold,
		@Param("batchSize") int batchSize
	);

	/**
	 * DEAD 이벤트 아카이브 테이블로 복사
	 */
	@Modifying
	@Query(value = """
        INSERT INTO outbox_event_archive
        SELECT * FROM outbox_event
        WHERE status = 'DEAD'
          AND completed_at < :threshold
        """, nativeQuery = true)
	int archiveDeadEvents(@Param("threshold") LocalDateTime threshold);

	/**
	 * DEAD 이벤트 삭제
	 */
	@Modifying
	@Query(value = """
        DELETE FROM outbox_event
        WHERE status = 'DEAD'
          AND completed_at < :threshold
        """, nativeQuery = true)
	int deleteDeadBefore(@Param("threshold") LocalDateTime threshold);
}
