package com.sudo.raillo.batch.train.infrastructure.redis;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기준정보 값을 Redis에 저장할 JSON 문자열로 바꾼다.
 */
@Component
public class TrainCacheJsonConverter {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		// 값 record가 @JsonFormat으로 형식을 고정하지만, 애너테이션 없는 필드가 추가돼도
		// 숫자 배열로 새지 않도록 기본 동작도 문자열로 맞춰 둔다
		.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
		.build();

	public String toJson(Object value) {
		return objectMapper.writeValueAsString(value);
	}
}
