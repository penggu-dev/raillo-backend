package com.sudo.raillo.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTypeResolverBuilder;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Map;
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

	public static final String TRAIN_CALENDAR_CACHE = "train:calendar";

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		// Record 타입의 Redis 직렬화/역직렬화 지원을 위한 커스텀 타입 리졸버 설정
		RecordSupportingTypeResolver typeResolver = new RecordSupportingTypeResolver(
			DefaultTyping.NON_FINAL,
			objectMapper.getPolymorphicTypeValidator()
		);
		typeResolver.init(JsonTypeInfo.Id.CLASS, null);
		typeResolver.inclusion(JsonTypeInfo.As.PROPERTY);
		objectMapper.setDefaultTyping(typeResolver);

		var serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

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

	static class RecordSupportingTypeResolver extends DefaultTypeResolverBuilder {
		public RecordSupportingTypeResolver(DefaultTyping t, PolymorphicTypeValidator ptv) {
			super(t, ptv);
		}

		@Override
		public boolean useForType(JavaType t) {
			Class<?> rawClass = t.getRawClass();

			// Java Record 타입이면 무조건 포함
			if (rawClass.isRecord()) {
				return true;
			}

			// 컬렉션도 타입 정보 포함
			if (java.util.Collection.class.isAssignableFrom(rawClass) ||
				java.util.Map.class.isAssignableFrom(rawClass)) {
				return true;
			}

			// 나머지는 기존 전략 따름
			return super.useForType(t);
		}
	}
}
