package com.sudo.raillo.booking.infrastructure.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** 예약 생성 Lua 스크립트. */
@Configuration
public class RedisScriptConfig {

	/**
	 * 예약 생성 스크립트. 좌석 점유 검사와 예약 저장을 원자적으로 처리한다.
	 *
	 * <p>반환값: {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationCreateScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_create.lua")));
		script.setResultType(List.class);
		return script;
	}
}
