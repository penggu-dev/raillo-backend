package com.sudo.raillo.global.event.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.event.domain.OutboxEvent;
import com.sudo.raillo.global.event.exception.EventError;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OutboxEventConverter {

	private final ObjectMapper objectMapper;

	public <T> T deserialize(OutboxEvent event, Class<T> tClass) {
		try {
			return objectMapper.readValue(event.getPayload(), tClass);
		} catch (JsonProcessingException e) {
			throw new BusinessException(EventError.EVENT_JSON_DESERIALIZATION_FAIL);
		}
	}

	public String serialize(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			throw new BusinessException(EventError.EVENT_JSON_SERIALIZATION_FAIL);
		}
	}

}
