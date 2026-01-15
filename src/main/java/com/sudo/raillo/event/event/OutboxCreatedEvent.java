package com.sudo.raillo.event.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Outbox 이벤트 생성 알림 (범용)
 * 모든 Outbox 이벤트가 생성될 때 발행되는 Spring Event
 * AFTER_COMMIT 시점에 즉시 처리 트리거
 */
@Getter
@RequiredArgsConstructor
public class OutboxCreatedEvent {

	private final Long outboxEventId;
}
