package com.sudo.raillo.event.handler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.event.domain.OutboxEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 이벤트 핸들러 레지스트리
 * Spring이 모든 OutboxEventHandler 구현체를 List로 자동 주입
 * → eventType을 Key로 하는 Map으로 변환하여 관리
 */
@Slf4j
@Component
public class OutboxEventHandlerRegistry {

	private final Map<String, OutboxEventHandler<?>> handlers;
	private final ObjectMapper objectMapper;

	/**
	 * Spring이 모든 OutboxEventHandler 구현체를 List로 자동 주입
	 */
	public OutboxEventHandlerRegistry(List<OutboxEventHandler<?>> handlerList,
		ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;

		this.handlers = handlerList.stream()
			.collect(Collectors.toMap(
				OutboxEventHandler::getEventType,
				Function.identity(),
				(existing, duplicate) -> {
					throw new IllegalStateException(
						"중복된 eventType 핸들러: " + existing.getEventType());
				}
			));

		// 등록된 핸들러 로깅
		handlers.forEach((eventType, handler) ->
			log.info("OutboxEventHandler 등록 - eventType: {}, handler: {}",
				eventType, handler.getClass().getSimpleName()));

		log.info("총 {} 개의 OutboxEventHandler 등록 완료", handlers.size());
	}

	/**
	 * 이벤트 처리 (eventType으로 핸들러 찾아서 실행)
	 */
	@SuppressWarnings("unchecked")
	public void handle(OutboxEvent event) {
		OutboxEventHandler<Object> handler =
			(OutboxEventHandler<Object>) handlers.get(event.getEventType());

		if (handler == null) {
			throw new IllegalArgumentException(
				"등록되지 않은 이벤트 타입: " + event.getEventType());
		}

		Object payload = parsePayload(event.getPayload(), handler.getPayloadClass());
		handler.handle(payload);
	}

	/**
	 * 핸들러 존재 여부 확인
	 */
	public boolean hasHandler(String eventType) {
		return handlers.containsKey(eventType);
	}

	/**
	 * 등록된 이벤트 타입 목록 조회
	 */
	public Set<String> getRegisteredEventTypes() {
		return handlers.keySet();
	}

	private Object parsePayload(String payload, Class<?> clazz) {
		try {
			return objectMapper.readValue(payload, clazz);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Payload 파싱 실패: " + e.getMessage(), e);
		}
	}
}
