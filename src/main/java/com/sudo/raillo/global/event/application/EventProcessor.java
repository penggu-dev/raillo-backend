package com.sudo.raillo.global.event.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.event.domain.Event;
import com.sudo.raillo.global.event.domain.EventError;
import com.sudo.raillo.global.event.domain.EventStatus;
import com.sudo.raillo.global.event.infrastructure.EventRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventProcessor {

	private final EventRepository eventRepository;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final ObjectMapper objectMapper;
	private final EventTypeMapper eventTypeMapper;

	@Transactional
	public void process(Long eventId) {
		// 1. 조회 + 락 획득
		Event event = eventRepository.findByIdWithLock(eventId)
			.orElseThrow(() -> new BusinessException(EventError.EVENT_NOT_FOUND));

		// 2. 상태 확인 (이미 완료되었거나 실패한 경우 스킵)
		if (event.getStatus() == EventStatus.COMPLETED || event.getStatus() == EventStatus.FAILED) {
			log.info("[이벤트 처리 스킵] eventId={}, status={}", eventId, event.getStatus());
			return;
		}

		// 3. 핸들러 실행 + 완료 처리 (같은 트랜잭션에서 처리)
		Object domainEvent = deserialize(event);
		applicationEventPublisher.publishEvent(domainEvent);

		// 4. 완료 처리 (핸들러 성공 시에만 도달)
		event.complete();
		log.debug("[이벤트 처리 완료] eventId={}", eventId);
	}

	private Object deserialize(Event event) {
		Class<?> eventClass = eventTypeMapper.getEventClass(event.getEventType());
		if (eventClass == null) {
			throw new BusinessException(EventError.UNKNOWN_EVENT);
		}
		try {
			return objectMapper.readValue(event.getPayload(), eventClass);
		} catch (JsonProcessingException e) {
			throw new BusinessException(EventError.EVENT_JSON_DESERIALIZATION_FAIL);
		}
	}
}
