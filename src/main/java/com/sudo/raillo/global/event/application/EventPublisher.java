package com.sudo.raillo.global.event.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.event.domain.Event;
import com.sudo.raillo.global.event.domain.EventError;
import com.sudo.raillo.global.event.infrastructure.EventRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;
	private final EventRepository eventRepository;
	private final ObjectMapper objectMapper;

	/**
	 * 도메인 이벤트를 이벤트 저장소에 저장합니다.
	 *
	 * @param aggregateType 집합체 타입 (예: "Payment")
	 * @param aggregateId   집합체 ID (예: paymentId)
	 * @param event         이벤트 객체
	 */
	public void publish(String aggregateType, Long aggregateId, Object event) {
		String eventType = event.getClass().getSimpleName();
		String payload = serialize(event);

		Event eventEntity = Event.create(
			aggregateType,
			aggregateId,
			eventType,
			payload
		);

		Event savedEvent = eventRepository.save(eventEntity);
		applicationEventPublisher.publishEvent(savedEvent);

		log.info("[이벤트 발행] eventId={}, aggregateType={}, aggregateId={}, eventType={}",
			savedEvent.getId(), aggregateType, aggregateId, eventType);
	}

	private String serialize(Object domainEvent) {
		try {
			return objectMapper.writeValueAsString(domainEvent);
		} catch (JsonProcessingException e) {
			log.error("[이벤트 직렬화 실패] event={}", domainEvent, e);
			throw new BusinessException(EventError.EVENT_JSON_SERIALIZATION_FAIL);
		}
	}
}
