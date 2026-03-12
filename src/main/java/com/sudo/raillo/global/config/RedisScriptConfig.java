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
 * <h3>DefaultRedisScript 제네릭 타입 설명</h3>
 * <p>{@code DefaultRedisScript<List>}에서 {@code List}는 <b>Lua 스크립트의 반환값 타입</b>입니다.</p>
 * <p>Lua에서 table을 반환하면 Java의 List로 변환됩니다:</p>
 * <pre>
 * // Lua
 * return {1, "SUCCESS"}
 *
 * // Java
 * List&lt;Object&gt; result = [1L, "SUCCESS"]
 * </pre>
 *
 * <h3>사용 방법</h3>
 * <p>@Bean 메서드 이름이 Bean 이름으로 사용되며, SeatHoldRepository에서 동일한 이름으로 주입받아 사용합니다.</p>
 *
 * <h3>Lua 스크립트 위치</h3>
 * <p>{@code src/main/resources/scripts/}</p>
 *
 * @see com.sudo.raillo.booking.infrastructure.SeatHoldRepository
 * @see com.sudo.raillo.booking.infrastructure.SeatHoldResult
 */
@Configuration
public class RedisScriptConfig {

	/**
	 * Seat Hold 스크립트
	 *
	 * <p>반환값: {@code {성공여부(1/0), 상태문자열, [충돌구간]}}</p>
	 * <p>예: {@code {1, "HOLD_SUCCESS"}} 또는 {@code {0, "CONFLICT_WITH_HOLD", "1-2"}}</p>
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
	 * Seat Hold 해제 스크립트
	 *
	 * <p>Seat Hold 키 삭제 및 Seat Hold Index에서 제거</p>
	 * <p>반환값: {@code {1, "RELEASE_SUCCESS"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> seatReleaseScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/seat_release.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * Seat Hold 점유 좌석 수 계산 스크립트
	 *
	 * <p>여러 TrainCar Hold Index를 조회하여 검색 구간과 겹치는 Seat Hold 좌석 수를 계산</p>
	 * <p>반환값: Seat Hold 점유 좌석 수 (Long)</p>
	 */
	@Bean
	public DefaultRedisScript<Long> getHoldSeatsCountScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/get_hold_seats_count.lua")));
		script.setResultType(Long.class);
		return script;
	}
}
