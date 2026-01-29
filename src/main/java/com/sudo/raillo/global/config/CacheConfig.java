package com.sudo.raillo.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@EnableCaching
@Configuration
public class CacheConfig {

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());

		//LocalDateTime, LocalDate를 문자열 형식("2024-01-29T10:00:00")으로 저장
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		var serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

		RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
			.defaultCacheConfig()
			.entryTtl(Duration.ofHours(1)) // 유효기간 전역 설정
			.serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer())) // key 직렬화
			.serializeValuesWith(SerializationPair.fromSerializer(serializer)); // value 직렬화

		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(defaultConfig)
			.build();
	}
}
