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
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_create.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 예약 삭제 스크립트. 자기 예약의 점유 field와 예약 본문만 지운다.
	 *
	 * <p>반환값: {@code {released}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationDeleteScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_delete.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 예매 점유 전환 스크립트. 자기 예약 점유를 예매 점유로 바꾸고 예약 본문을 지운다.
	 *
	 * <p>반환값: {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"|"X"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationBookingConfirmScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_booking_confirm.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 결제 중 좌석 보호 스크립트. 자기 예약 점유의 만료를 없애고 사라진 field를 다시 점유한다.
	 *
	 * <p>반환값: {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"|"X"}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationPaymentHoldScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_payment_hold.lua")));
		script.setResultType(List.class);
		return script;
	}

	/**
	 * 결제 중 좌석 보호 해제 스크립트. 자기 예약 점유의 만료를 보호 이전 상태로 되돌린다.
	 *
	 * <p>반환값: {@code {restoredCount, deletedCount}}</p>
	 */
	@Bean
	public DefaultRedisScript<List> reservationPaymentReleaseScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptSource(new ResourceScriptSource(
			new ClassPathResource("scripts/reservation_payment_release.lua")));
		script.setResultType(List.class);
		return script;
	}
}
