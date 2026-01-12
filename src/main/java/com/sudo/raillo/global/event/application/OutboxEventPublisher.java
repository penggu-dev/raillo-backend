package com.sudo.raillo.global.event.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.event.domain.OutboxEvent;
import com.sudo.raillo.global.event.exception.EventError;
import com.sudo.raillo.global.event.infrastructure.OutboxEventRepository;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	public OutboxEvent publish(String aggregateType, Long aggregateId, Object event) {
		try {
			String eventType = event.getClass().getSimpleName();
			String payload = objectMapper.writeValueAsString(event);

			OutboxEvent outboxEvent = OutboxEvent.create(
				aggregateType,
				aggregateId,
				eventType,
				payload
			);
			return outboxEventRepository.save(outboxEvent);

		} catch (JsonProcessingException e) {
			log.error("[이벤트 직렬화 실패] aggregateType={}, aggregateId={}, eventType={}",
				aggregateType, aggregateId, event.getClass().getSimpleName(), e);
			throw new BusinessException(EventError.EVENT_JSON_SERIALIZATION_FAIL);
		}
	}
}
