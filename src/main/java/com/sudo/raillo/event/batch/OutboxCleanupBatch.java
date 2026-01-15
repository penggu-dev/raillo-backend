package com.sudo.raillo.event.batch;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.event.infrastructure.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 이벤트 정리 배치
 * 처리 완료된 이벤트를 주기적으로 삭제/아카이빙
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupBatch {

	private final OutboxEventRepository outboxRepository;

	private static final int BATCH_SIZE = 5000;
	private static final int COMPLETED_RETENTION_DAYS = 30;
	private static final int DEAD_RETENTION_DAYS = 90;

	/**
	 * 매일 새벽 3시 - COMPLETED 정리 (30일 경과)
	 */
	@Scheduled(cron = "0 0 3 * * *")
	@Transactional
	public void cleanupCompletedEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(COMPLETED_RETENTION_DAYS);

		int totalDeleted = 0;
		int deleted;

		// batch 단위로 삭제 (락 최소화)
		do {
			deleted = outboxRepository.deleteCompletedBefore(threshold, BATCH_SIZE);
			totalDeleted += deleted;

			if (deleted > 0) {
				log.info("Outbox COMPLETED 정리 진행 중 - {} 건 삭제", deleted);
			}
		} while (deleted == BATCH_SIZE);

		if (totalDeleted > 0) {
			log.info("Outbox COMPLETED 정리 완료 - 총 {} 건 삭제 (기준: {} 이전)",
				totalDeleted, threshold);
		}
	}

	/**
	 * 매주 일요일 새벽 4시 - DEAD 아카이빙 (90일 경과)
	 */
	@Scheduled(cron = "0 0 4 * * SUN")
	@Transactional
	public void archiveDeadEvents() {
		LocalDateTime threshold = LocalDateTime.now().minusDays(DEAD_RETENTION_DAYS);

		try {
			// 1. 아카이브 테이블로 복사
			int archived = outboxRepository.archiveDeadEvents(threshold);

			// 2. 원본 삭제
			int deleted = outboxRepository.deleteDeadBefore(threshold);

			if (archived > 0 || deleted > 0) {
				log.info("Outbox DEAD 아카이빙 완료 - 아카이브: {} 건, 삭제: {} 건 (기준: {} 이전)",
					archived, deleted, threshold);
			}
		} catch (Exception e) {
			// 아카이브 테이블이 없으면 삭제만 수행
			log.warn("아카이브 테이블 없음, 삭제만 수행: {}", e.getMessage());

			int deleted = outboxRepository.deleteDeadBefore(threshold);
			if (deleted > 0) {
				log.info("Outbox DEAD 삭제 완료 - {} 건 (기준: {} 이전)", deleted, threshold);
			}
		}
	}
}
