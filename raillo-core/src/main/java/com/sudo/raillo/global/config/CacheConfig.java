package com.sudo.raillo.global.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper.Builder;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.json.JsonMapper;

@EnableCaching
@Configuration
public class CacheConfig {

	public static final String TRAIN_CALENDAR_CACHE = "train:calendar";

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		Builder objectMapperBuilder = JsonMapper.builder().findAndAddModules();
		objectMapperBuilder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);

		BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
			.allowIfSubType("com.sudo.raillo.")
			.allowIfSubType("java.math.")
			.allowIfSubType("java.time.")
			.allowIfSubType("java.util.")
			.allowIfSubTypeIsArray()
			.build();
		objectMapperBuilder.activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL_AND_RECORDS);
		ObjectMapper objectMapper = objectMapperBuilder.build();

		GenericJacksonJsonRedisSerializer serializer = new GenericJacksonJsonRedisSerializer(objectMapper);

		RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
			.defaultCacheConfig()
			.entryTtl(Duration.ofHours(1)) // 유효기간 전역 설정
			.serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer())) // key 직렬화
			.serializeValuesWith(SerializationPair.fromSerializer(serializer)); // value 직렬화

		// train:calendar 캐시는 1일 TTL 설정 (자정에 스케줄러가 삭제)
		RedisCacheConfiguration calendarConfig = defaultConfig.entryTtl(Duration.ofDays(1));

		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(defaultConfig)
			.withInitialCacheConfigurations(Map.of(TRAIN_CALENDAR_CACHE, calendarConfig))
			.build();
	}
}
