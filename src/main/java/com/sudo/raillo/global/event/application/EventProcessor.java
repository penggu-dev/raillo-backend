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

		// 2. 상태 확인 (이미 완료된 경우 스킵)
		if (event.getStatus() == EventStatus.COMPLETED) {
			log.info("[이벤트 이미 처리됨] eventId={}, status={}", eventId, event.getStatus());
			return;
		}

		// 3. 핸들러 실행
		try {
			Object domainEvent = deserialize(event);
			applicationEventPublisher.publishEvent(domainEvent);
		} catch (Exception e) {
			log.error("[핸들러 실행 실패] eventId={}, error={}", eventId, e.getMessage(), e);
			event.fail();
			return;
		}

		// 4. 완료 처리
		event.complete();
		log.debug("[이벤트 처리 완료] eventId={}", eventId);
	}

	private Object deserialize(Event event) throws JsonProcessingException {
		Class<?> eventClass = eventTypeMapper.getEventClass(event.getEventType());
		if (eventClass == null) {
			throw new BusinessException(EventError.UNKNOWN_EVENT);
		}
		return objectMapper.readValue(event.getPayload(), eventClass);
	}
}
