package com.sudo.raillo.global.redis.util;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.redis.exception.RedisError;
import com.sudo.raillo.global.redis.exception.RedisException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis에 평문 JSON으로 저장하는 값의 변환기.
 */
@Component
public class RedisJsonConverter {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
		.build();

	public String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new RedisException(RedisError.SERIALIZATION_FAIL, e);
		}
	}

	public <T> T fromJson(String json, Class<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (JacksonException e) {
			throw new RedisException(RedisError.SERIALIZATION_FAIL, e);
		}
	}
}
