package com.sudo.raillo.booking.infrastructure.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** 예약 생성·삭제와 좌석 점유 Lua 스크립트. */
@Configuration
public class RedisScriptConfig {

	/**
	 * 예약 생성 스크립트. 좌석 점유 검사와 예약 저장을 원자적으로 처리한다.
	 *
	 * <p>반환값: {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationCreateScript() {
		return listScript("scripts/reservation_create.lua");
	}

	/**
	 * 예약 삭제 스크립트. 자기 예약의 점유 field와 예약 본문만 지운다.
	 *
	 * <p>반환값: {@code {released}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationDeleteScript() {
		return listScript("scripts/reservation_delete.lua");
	}

	/**
	 * 예매 점유 전환 스크립트. 자기 예약 점유를 예매 점유로 바꾸고 예약 본문을 지운다.
	 *
	 * <p>반환값: {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"|"X"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationBookingConfirmScript() {
		return listScript("scripts/reservation_booking_confirm.lua");
	}

	/** 모든 좌석 점유 스크립트는 List를 반환하므로 등록 방식이 같다. 빈마다 다른 것은 경로와 반환 계약뿐이다. */
	private static DefaultRedisScript<List> listScript(String classpath) {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpath)));
		script.setResultType(List.class);
		return script;
	}
}
