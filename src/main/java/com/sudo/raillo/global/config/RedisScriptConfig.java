package com.sudo.raillo.global.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis Lua 스크립트 Bean 설정
 *
 * <p> @Bean 메서드 이름이 Bean 이름으로 사용됨</p>
 * <p>- SeatHoldRepository에서 동일한 이름으로 주입받아 사용</p>
 *
 * Lua 스크립트 위치: src/main/resources/scripts/
 *
 * @see com.sudo.raillo.booking.infrastructure.SeatHoldRepository
 */
@Configuration
public class RedisScriptConfig {

	/**
	 * 좌석 임시 점유 스크립트
	 */
	@Bean
	public DefaultRedisScript<List> seatHoldScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/seat_hold.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 좌석 확정 스크립트 (Hold → Sold)
	 */
	@Bean
	public DefaultRedisScript<List> seatConfirmScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/seat_confirm.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 좌석 점유 해제 스크립트
	 */
	@Bean
	public DefaultRedisScript<List> seatReleaseScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/seat_release.lua")));
		script.setResultType(List.class);
		return script;
	}
}
