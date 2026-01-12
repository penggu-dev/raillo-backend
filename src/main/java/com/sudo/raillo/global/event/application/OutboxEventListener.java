package com.sudo.raillo.global.event.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sudo.raillo.global.event.domain.OutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

	private final OutboxEventHandler outboxEventHandler;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleOutboxEvent(OutboxEvent event) {
		try {
			outboxEventHandler.handle(event.getId());
			log.info("[이벤트 즉시 처리 완료] eventType={}, eventId={}", event.getEventType(), event.getId());
		} catch (Exception e) {
			log.warn("[이벤트 즉시 처리 실패 - 폴링 재시도 예정] eventId={}", event.getId(), e);
		}
	}
}
