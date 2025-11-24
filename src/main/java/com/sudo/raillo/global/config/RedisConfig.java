package com.sudo.raillo.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sudo.raillo.booking.domain.ProvisionalBooking;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableRedisRepositories
public class RedisConfig {

	@Value("${spring.data.redis.host}")
	private String host;

	@Value("${spring.data.redis.port}")
	private int port;

	@Bean
	public RedisConnectionFactory redisConnectionFactory() {

		RedisStandaloneConfiguration redisConf = new RedisStandaloneConfiguration();
		redisConf.setHostName(host);
		redisConf.setPort(port);
		LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(redisConf);
		return lettuceConnectionFactory;
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

		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());

		GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
		redisTemplate.setDefaultSerializer(serializer);
		redisTemplate.setValueSerializer(serializer);
		redisTemplate.setKeySerializer(new StringRedisSerializer());

		return redisTemplate;
	}

	/**
	 * Redisson Client - 분산 락용
	 */
	@Bean
	public RedissonClient redissonClient() {
		Config config = new Config();
		config.useSingleServer()
			.setAddress("redis://" + host + ":" + port);
		return Redisson.create(config);
	}

	/**
	 * ProvisionalBooking 전용 RedisTemplate
	 * 타입 안정성을 위해 분리
	 */
	@Bean
	public RedisTemplate<String, ProvisionalBooking> provisionalBookingRedisTemplate() {
		RedisTemplate<String, ProvisionalBooking> redisTemplate = new RedisTemplate<>();
		redisTemplate.setConnectionFactory(redisConnectionFactory());

		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
		redisTemplate.setValueSerializer(serializer);
		redisTemplate.setKeySerializer(new StringRedisSerializer());

		return redisTemplate;
	}
}
