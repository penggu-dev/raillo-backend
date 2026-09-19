package com.sudo.raillo.batch.train.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.sudo.raillo.batch.support.BatchTestContainerInitializer;

/**
 * 파이프라인 한 묶음을 3으로 줄여, 모든 다건 쓰기가 묶음 경계를 넘도록 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "train.cache.pipeline-size=3")
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
@DisplayName("TrainCacheRedisRepository - 기준정보 대량 쓰기")
class TrainCacheRedisRepositoryTest {

	private static final long TTL_TOLERANCE_SECONDS = 5L;

	@Autowired
	private TrainCacheRedisRepository trainCacheRedisRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@BeforeEach
	void clearRedis() {
		stringRedisTemplate.execute((RedisCallback<Object>)connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	@Nested
	@DisplayName("문자열 키 저장")
	class SaveValues {

		@Test
		@DisplayName("묶음 크기를 넘는 항목도 빠짐없이 저장된다")
		void savesMoreEntriesThanChunkSize() {
			// given - 묶음 크기가 3이므로 10건은 네 묶음으로 나뉜다
			Map<String, String> entries = IntStream.rangeClosed(1, 10)
				.boxed()
				.collect(Collectors.toMap("train:seat:%d"::formatted, id -> "{\"seatRow\":" + id + "}"));

			// when
			trainCacheRedisRepository.saveValues(entries);

			// then
			assertThat(stringRedisTemplate.opsForValue().multiGet(entries.keySet()))
				.hasSize(10)
				.doesNotContainNull();
			assertThat(stringRedisTemplate.opsForValue().get("train:seat:7"))
				.isEqualTo("{\"seatRow\":7}");
		}

		@Test
		@DisplayName("정적 기준정보는 만료 시각을 갖지 않는다")
		void storesWithoutExpiry() {
			// given

			// when
			trainCacheRedisRepository.saveValues(Map.of("train:station:1", "서울"));

			// then - TTL이 없는 키는 -1을 반환한다
			assertThat(stringRedisTemplate.getExpire("train:station:1", TimeUnit.SECONDS)).isEqualTo(-1L);
		}

		@Test
		@DisplayName("빈 항목을 넘겨도 아무 일도 하지 않는다")
		void ignoresEmptyInput() {
			// given

			// when
			trainCacheRedisRepository.saveValues(Map.of());

			// then
			assertThat(trainCacheRedisRepository.scanKeys("train:*")).isEmpty();
		}
	}

	@Nested
	@DisplayName("만료 시각 지정 저장")
	class SaveExpiring {

		@Test
		@DisplayName("적재 시점이 아니라 지정한 절대 시각에 만료된다")
		void expiresAtAbsoluteTime() {
			// given - 운행일 기준으로 계산된 미래 시각
			long expireAt = Instant.now().plus(Duration.ofDays(2)).getEpochSecond();

			// when
			trainCacheRedisRepository.saveValuesExpiringAt(
				Map.of("{schedule:1001}:info", "{\"trainScheduleId\":1001}"), expireAt);

			// then
			long expectedRemaining = expireAt - Instant.now().getEpochSecond();
			assertThat(stringRedisTemplate.getExpire("{schedule:1001}:info", TimeUnit.SECONDS))
				.isCloseTo(expectedRemaining, within(TTL_TOLERANCE_SECONDS));
		}

		@Test
		@DisplayName("Hash 여러 개를 field와 만료 시각까지 한 번에 저장한다")
		void savesHashesWithExpiry() {
			// given
			long expireAt = Instant.now().plus(Duration.ofDays(2)).getEpochSecond();
			Map<String, Map<String, String>> hashes = Map.of(
				"{schedule:1001}:stops", Map.of("st:1", "{\"stopOrder\":1}", "id:9001", "{\"stopOrder\":1}"),
				"{schedule:1002}:stops", Map.of("st:1", "{\"stopOrder\":1}")
			);

			// when
			trainCacheRedisRepository.saveHashesExpiringAt(hashes, expireAt);

			// then
			assertThat(trainCacheRedisRepository.hashFieldNames("{schedule:1001}:stops"))
				.containsExactlyInAnyOrder("st:1", "id:9001");
			assertThat(stringRedisTemplate.getExpire("{schedule:1002}:stops", TimeUnit.SECONDS))
				.isCloseTo(expireAt - Instant.now().getEpochSecond(), within(TTL_TOLERANCE_SECONDS));
		}
	}

	@Nested
	@DisplayName("Hash field 저장과 정리")
	class HashFields {

		@Test
		@DisplayName("묶음 크기를 넘는 field도 한 Hash에 모두 들어간다")
		void savesAllFields() {
			// given
			Map<String, String> fares = IntStream.rangeClosed(1, 7)
				.boxed()
				.collect(Collectors.toMap(id -> "1:" + id, id -> id + "00:" + id + "50"));

			// when
			trainCacheRedisRepository.saveHashFields("train:fare", fares);

			// then
			assertThat(trainCacheRedisRepository.hashFieldNames("train:fare")).hasSize(7);
			assertThat(stringRedisTemplate.<String, String>opsForHash().get("train:fare", "1:5"))
				.isEqualTo("500:550");
		}

		@Test
		@DisplayName("지정한 field만 지우고 나머지는 남긴다")
		void deletesOnlyGivenFields() {
			// given
			trainCacheRedisRepository.saveHashFields("train:fare",
				Map.of("1:2", "100:150", "1:3", "200:250", "1:4", "300:350"));

			// when - 운임표가 교체되면서 사라진 구간을 지우는 상황
			trainCacheRedisRepository.deleteHashFields("train:fare", List.of("1:3"));

			// then
			assertThat(trainCacheRedisRepository.hashFieldNames("train:fare"))
				.containsExactlyInAnyOrder("1:2", "1:4");
		}
	}

	@Nested
	@DisplayName("키 훑기와 삭제")
	class ScanAndDelete {

		@Test
		@DisplayName("패턴에 맞는 키만 모은다")
		void scansByPattern() {
			// given
			trainCacheRedisRepository.saveValues(Map.of(
				"train:seat:1", "a", "train:seat:2", "b", "train:traincar:1", "c"));

			// when
			Set<String> seatKeys = trainCacheRedisRepository.scanKeys("train:seat:*");

			// then
			assertThat(seatKeys).containsExactlyInAnyOrder("train:seat:1", "train:seat:2");
		}

		@Test
		@DisplayName("지정한 키를 지운다")
		void deletesKeys() {
			// given
			trainCacheRedisRepository.saveValues(Map.of("train:seat:1", "a", "train:seat:2", "b"));

			// when
			trainCacheRedisRepository.deleteKeys(List.of("train:seat:1"));

			// then
			assertThat(trainCacheRedisRepository.scanKeys("train:seat:*"))
				.containsExactly("train:seat:2");
		}
	}
}
