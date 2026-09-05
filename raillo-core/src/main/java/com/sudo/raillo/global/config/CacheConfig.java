package com.sudo.raillo.global.config;

import com.sudo.raillo.train.application.dto.response.OperationCalendarItemResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.json.JsonMapper;

@EnableCaching
@Configuration
public class CacheConfig {

	public static final String TRAIN_CALENDAR_CACHE = "train:calendar";

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		// Redis Cache의 저장·삭제 작업을 즉시 반영하도록 설정
		RedisCacheWriter cacheWriter = RedisCacheWriter.create(connectionFactory,
			RedisCacheWriter.RedisCacheWriterConfigurer::immediateWrites
		);

		// 모든 캐시에 적용되는 기본 설정
		RedisCacheConfiguration defaultConfig = createDefaultCacheConfig();

		// train:calendar 캐시 전용 설정
		RedisCacheConfiguration calendarConfig = createCalendarCacheConfig(defaultConfig);

		return RedisCacheManager.builder(cacheWriter)
			.cacheDefaults(defaultConfig)
			.withInitialCacheConfigurations(Map.of(TRAIN_CALENDAR_CACHE, calendarConfig)).build();
	}

	private RedisCacheConfiguration createDefaultCacheConfig() {
		return RedisCacheConfiguration
			.defaultCacheConfig()
			.entryTtl(Duration.ofHours(1))
			.serializeKeysWith(SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
			.serializeValuesWith(SerializationPair.fromSerializer(createDefaultValueSerializer()));
	}

	private RedisCacheConfiguration createCalendarCacheConfig(RedisCacheConfiguration defaultConfig) {
		ObjectMapper objectMapper = createObjectMapper();

		// List<OperationCalendarItemResponse>의 실제 타입 정보를 구성
		JavaType calendarType = objectMapper.getTypeFactory()
			.constructCollectionType(List.class, OperationCalendarItemResponse.class);

		// train:calendar 전용 타입 지정 JSON serializer
		var calendarSerializer = new JacksonJsonRedisSerializer<>(objectMapper, calendarType);

		return defaultConfig
			.entryTtl(Duration.ofDays(1)) // 저장 시점부터 24시간 유지
			.disableCachingNullValues() // null 반환 결과는 캐싱하지 않음
			// List<OperationCalendarItemResponse> 타입으로 저장·조회
			.serializeValuesWith(SerializationPair.fromSerializer(calendarSerializer));
	}

	private ObjectMapper createObjectMapper() {
		return JsonMapper.builder()
			.findAndAddModules()
			.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
			.build();
	}

	private GenericJacksonJsonRedisSerializer createDefaultValueSerializer() {
		PolymorphicTypeValidator typeValidator =
			BasicPolymorphicTypeValidator.builder()
				// 애플리케이션 타입 허용
				.allowIfSubType("com.sudo.raillo.")
				// List, Map 등의 컬렉션 타입 허용
				.allowIfSubType("java.util.")
				.build();

		return GenericJacksonJsonRedisSerializer.create(builder -> {
			builder
				.enableSpringCacheNullValueSupport()
				.customize(mapperBuilder -> {mapperBuilder
					.findAndAddModules()
					.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
					.activateDefaultTypingAsProperty(
						typeValidator,
						DefaultTyping.NON_FINAL_AND_RECORDS,
						"@class"
					);
				});
		});
	}
}
