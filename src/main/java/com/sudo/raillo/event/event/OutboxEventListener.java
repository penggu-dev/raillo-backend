package com.sudo.raillo.event.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 이벤트 리스너 (범용)
 * AFTER_COMMIT 시점에 즉시 처리 수행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

	private final OutboxEventProcessor outboxProcessor;

	/**
	 * 트랜잭션 커밋 후 즉시 처리
	 * - 성공: PENDING → COMPLETED
	 * - 실패: PENDING → FAILED (배치가 재처리)
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleOutboxCreated(OutboxCreatedEvent event) {
		log.debug("OutboxCreatedEvent 수신 - outboxEventId: {}", event.getOutboxEventId());
		outboxProcessor.processImmediately(event.getOutboxEventId());
	}
}
