package com.sudo.raillo.global.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
@DisplayName("Redis 연결 설정")
class RedisConfigTest {

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	@Test
	@DisplayName("명령 타임아웃을 설정하지 않으면 Lettuce 기본값 60초가 아니라 1초를 쓴다")
	void commandTimeoutDefaultsToOneSecond() {
		// given
		LettuceConnectionFactory factory = (LettuceConnectionFactory)redisConnectionFactory;

		// when
		Duration timeout = factory.getClientConfiguration().getCommandTimeout();

		// then
		assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
	}
}
