package com.sudo.raillo.event.event;

import org.springframework.stereotype.Component;

import com.sudo.raillo.event.domain.OutboxEvent;
import com.sudo.raillo.event.domain.OutboxStatus;
import com.sudo.raillo.event.handler.OutboxEventHandlerRegistry;
import com.sudo.raillo.event.infrastructure.OutboxEventRepository;
import com.sudo.raillo.global.infrastructure.PodIdentifier;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 이벤트 즉시 처리기 (범용)
 * AFTER_COMMIT 시점에 호출되어 이벤트 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProcessor {

	private final OutboxEventRepository outboxRepository;
	private final OutboxEventHandlerRegistry handlerRegistry;
	private final PodIdentifier podIdentifier;

	/**
	 * 즉시 처리 (AFTER_COMMIT에서 호출)
	 * 범용 - 모든 이벤트 타입 처리 가능
	 *
	 * @param outboxEventId 처리할 OutboxEvent ID
	 */
	@Transactional
	public void processImmediately(Long outboxEventId) {
		OutboxEvent outbox = outboxRepository.findById(outboxEventId)
			.orElseThrow(() -> new IllegalStateException("OutboxEvent not found: " + outboxEventId));

		// 멱등성 체크: 이미 처리된 이벤트면 스킵
		if (outbox.getStatus() != OutboxStatus.PENDING) {
			log.info("이미 처리된 이벤트 - outboxId: {}, status: {}", outboxEventId, outbox.getStatus());
			return;
		}

		String eventType = outbox.getEventType();
		String podId = podIdentifier.get();

		try {
			// Registry에서 핸들러 찾아서 실행
			handlerRegistry.handle(outbox);

			// 성공: PENDING → COMPLETED
			outbox.complete(podId);

			log.info("즉시 처리 완료 - outboxId: {}, eventType: {}, pod: {}", outboxEventId, eventType, podId);

		} catch (Exception e) {
			// 실패: PENDING → FAILED (배치가 재처리)
			outbox.fail(e.getMessage());

			log.warn("즉시 처리 실패, 배치로 재시도 예정 - outboxId: {}, eventType: {}, error: {}", outboxEventId, eventType, e.getMessage());
		}
	}
}
