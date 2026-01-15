package com.sudo.raillo.event.event;

import org.springframework.stereotype.Component;

import com.sudo.raillo.event.domain.OutboxEvent;

import lombok.RequiredArgsConstructor;

/**
 * Outbox 이벤트 발행 헬퍼
 * Service에서 Outbox 저장 + Spring Event 발행을 간편하게 사용
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

	private final OutboxEventRepository outboxRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final ObjectMapper objectMapper;

	/**
	 * Outbox 이벤트 발행
	 * 1. OutboxEvent 저장 (PENDING)
	 * 2. Spring Event 등록 (AFTER_COMMIT에서 처리)
	 *
	 * @param aggregateType 집계 타입 (e.g., "Payment")
	 * @param aggregateId 집계 ID (e.g., paymentId)
	 * @param eventType 이벤트 타입 (e.g., "PaymentCompletedEvent")
	 * @param payload 이벤트 데이터 객체
	 * @return 저장된 OutboxEvent
	 */
	public OutboxEvent publish(String aggregateType, Long aggregateId,
		String eventType, Object payload) {
		OutboxEvent outbox = OutboxEvent.create(
			aggregateType,
			aggregateId,
			eventType,
			toJson(payload)
		);
		outboxRepository.save(outbox);

		// AFTER_COMMIT 시점에 즉시 처리 트리거
		eventPublisher.publishEvent(new OutboxCreatedEvent(outbox.getId()));

		return outbox;
	}

	private String toJson(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("JSON 직렬화 실패", e);
		}
	}
}
