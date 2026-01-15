package com.sudo.raillo.event.batch;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.sudo.raillo.event.domain.OutboxEvent;
import com.sudo.raillo.event.handler.OutboxEventHandlerRegistry;
import com.sudo.raillo.event.infrastructure.OutboxEventRepository;

/**
 * Outbox 이벤트 재처리 배치
 * 실패한 이벤트들을 주기적으로 재처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRetryBatch {

	private final OutboxEventRepository outboxRepository;
	private final OutboxEventHandlerRegistry handlerRegistry;
	private final PodIdentifier podIdentifier;

	private static final int BATCH_SIZE = 20;
	private static final Duration PENDING_THRESHOLD = Duration.ofMinutes(5);
	private static final Duration PROCESSING_THRESHOLD = Duration.ofMinutes(10);

	/**
	 * 재처리 배치 (5분마다)
	 * 각 쿼리가 Index Range Scan으로 실행됨 (OR 조건 없음)
	 */
	@Scheduled(fixedRate = 300_000)  // 5분
	public void retryEvents() {
		int totalProcessed = 0;

		// 1. FAILED (우선순위 높음 - 이미 실패 확정)
		totalProcessed += processEvents(
			outboxRepository.findFailedEvents(BATCH_SIZE),
			"FAILED"
		);

		// 2. PENDING 유실 (5분 경과 - AFTER_COMMIT 실행 안 됨)
		totalProcessed += processEvents(
			outboxRepository.findStalePendingEvents(
				LocalDateTime.now().minus(PENDING_THRESHOLD),
				BATCH_SIZE
			),
			"STALE_PENDING"
		);

		// 3. PROCESSING 좀비 (10분 경과 - 배치 처리 중 크래시)
		totalProcessed += processEvents(
			outboxRepository.findStuckProcessingEvents(
				LocalDateTime.now().minus(PROCESSING_THRESHOLD),
				BATCH_SIZE
			),
			"STUCK_PROCESSING"
		);

		if (totalProcessed > 0) {
			log.info("배치 완료 - 총 {} 건, pod: {}", totalProcessed, podIdentifier.get());
		}
	}

	private int processEvents(List<OutboxEvent> events, String category) {
		if (events.isEmpty()) {
			return 0;
		}

		log.info("{} 이벤트 {} 건 처리 시작 - pod: {}",
			category, events.size(), podIdentifier.get());

		for (OutboxEvent event : events) {
			processEvent(event);
		}

		return events.size();
	}

	@Transactional
	protected void processEvent(OutboxEvent event) {
		String podId = podIdentifier.get();

		// PROCESSING으로 전환 (선점 표시)
		event.startProcessing(podId);
		outboxRepository.saveAndFlush(event);

		try {
			// Registry에서 핸들러 찾아서 실행
			handlerRegistry.handle(event);

			// 성공: PROCESSING → COMPLETED
			event.complete(podId);

			log.info("재처리 성공 - eventId: {}, type: {}",
				event.getId(), event.getEventType());

		} catch (Exception e) {
			// 실패: PROCESSING → FAILED or DEAD
			event.fail(e.getMessage());

			log.warn("재처리 실패 - eventId: {}, retry: {}/{}, error: {}",
				event.getId(), event.getRetryCount(), event.getMaxRetries(), e.getMessage());

			if (event.isDead()) {
				handleDeadEvent(event);
			}
		}

		outboxRepository.save(event);
	}

	/**
	 * DEAD 이벤트 처리 - 보상 트랜잭션 발행
	 */
	private void handleDeadEvent(OutboxEvent event) {
		log.error("이벤트 최종 실패 - eventId: {}, type: {}, error: {}",
			event.getId(), event.getEventType(), event.getLastError());

		// 결제 완료 이벤트가 DEAD → 자동 환불 이벤트 발행
		if ("PaymentCompletedEvent".equals(event.getEventType())) {
			OutboxEvent compensation = OutboxEvent.create(
				event.getAggregateType(),
				event.getAggregateId(),
				"PaymentCompensationEvent",
				event.getPayload()
			);
			outboxRepository.save(compensation);

			log.warn("보상 이벤트 발행 - paymentId: {}", event.getAggregateId());
		}

		// TODO: Discord 알림 발송
		// alertService.sendDeadEventAlert(event);
	}
}
