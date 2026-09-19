package com.sudo.raillo.global.config;

import io.lettuce.core.api.StatefulConnection;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration
@EnableRedisRepositories
public class RedisConfig {

	@Value("${spring.data.redis.host}")
	private String host;

	@Value("${spring.data.redis.port}")
	private int port;

	@Value("${spring.data.redis.ssl.enabled:false}")
	private boolean sslEnabled;

	@Value("${spring.data.redis.lettuce.pool.max-active:16}")
	private int poolMaxActive;

	@Value("${spring.data.redis.lettuce.pool.max-wait:500ms}")
	private Duration poolMaxWait;

	@Bean
	public RedisConnectionFactory redisConnectionFactory() {

		RedisStandaloneConfiguration redisConf = new RedisStandaloneConfiguration();
		redisConf.setHostName(host);
		redisConf.setPort(port);

		// maxIdle이 maxTotal보다 작으면 반납된 연결이 닫혀 연결 생성·종료가 다시 반복된다
		GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
		poolConfig.setMaxTotal(poolMaxActive);
		poolConfig.setMaxIdle(poolMaxActive);
		poolConfig.setMaxWait(poolMaxWait);

		LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder clientConfig =
			LettucePoolingClientConfiguration.builder().poolConfig(poolConfig);
		if (sslEnabled) {
			clientConfig.useSsl();
		}

		return new LettuceConnectionFactory(redisConf, clientConfig.build());
	}

	@Bean
	public RedisTemplate<String, String> customStringRedisTemplate() {

		RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
		redisTemplate.setConnectionFactory(redisConnectionFactory());

		redisTemplate.setKeySerializer(new StringRedisSerializer());
		redisTemplate.setValueSerializer(new StringRedisSerializer());

		return redisTemplate;
	}

	@Bean
	public RedisTemplate<String, Object> customObjectRedisTemplate() {
		RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
		redisTemplate.setConnectionFactory(redisConnectionFactory());

		GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
			.enableUnsafeDefaultTyping()
			.build();
		redisTemplate.setDefaultSerializer(serializer);
		redisTemplate.setValueSerializer(serializer);
		redisTemplate.setKeySerializer(new StringRedisSerializer());

		return redisTemplate;
	}
}
