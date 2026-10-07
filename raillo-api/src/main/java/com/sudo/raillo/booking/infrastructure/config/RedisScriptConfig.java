package com.sudo.raillo.booking.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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

	/**
	 * Lua 스크립트를 등록한다. 모든 스크립트가 List를 반환하므로 등록 방식이 같고, 빈마다 다른 것은
	 * 경로와 반환 계약뿐이다.
	 *
	 * <p>스크립트 본문을 기동 시점에 읽어 읽기 실패를 즉시 드러낸다. 읽은 텍스트는 Bean에 그대로
	 * 담기므로 런타임에 파일을 다시 열지 않는다.</p>
	 */
	private static DefaultRedisScript<List> listScript(String classpath) {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setScriptText(read(classpath));
		script.setResultType(List.class);
		return script;
	}

	private static String read(String classpath) {
		try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Lua 스크립트를 읽을 수 없습니다: " + classpath, e);
		}
	}
}
