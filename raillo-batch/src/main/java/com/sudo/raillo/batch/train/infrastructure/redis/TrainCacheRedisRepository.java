package com.sudo.raillo.batch.train.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.batch.train.config.TrainCacheProperties;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrainCacheRedisRepository {

	private final StringRedisTemplate stringRedisTemplate;
	private final TrainCacheProperties trainCacheProperties;

	/**
	 * 만료 없는 문자열 키들을 저장한다. 정적 기준정보가 쓴다.
	 */
	public void saveValues(Map<String, String> entries) {
		forEachChunk(entries, chunk -> stringRedisTemplate.executePipelined(
			(RedisCallback<Object>)connection -> {
				chunk.forEach(entry -> connection.stringCommands()
					.set(toBytes(entry.getKey()), toBytes(entry.getValue())));
				return null;
			}));
	}

	/**
	 * 만료 시각을 가진 문자열 키들을 저장한다. 운행 정보가 쓴다.
	 *
	 * @param expireAtEpochSecond 만료 시각 (Unix epoch 초). 적재 시점 기준 상대 TTL이 아니다
	 */
	public void saveValuesExpiringAt(Map<String, String> entries, long expireAtEpochSecond) {
		forEachChunk(entries, chunk -> stringRedisTemplate.executePipelined(
			(RedisCallback<Object>)connection -> {
				chunk.forEach(entry -> {
					byte[] key = toBytes(entry.getKey());
					connection.stringCommands().set(key, toBytes(entry.getValue()));
					connection.keyCommands().expireAt(key, expireAtEpochSecond);
				});
				return null;
			}));
	}

	/**
	 * 만료 없는 Hash에 field들을 넣는다. 기존 field는 남으므로 정리는 호출하는 쪽이 한다.
	 */
	public void saveHashFields(String key, Map<String, String> fields) {
		HashOperations<String, String, String> hashOperations = stringRedisTemplate.opsForHash();
		forEachChunk(fields, chunk -> hashOperations.putAll(key, toMap(chunk)));
	}

	/**
	 * 만료 시각을 가진 Hash 여러 개를 저장한다. 정차역이 쓴다.
	 *
	 * @param hashes 키별 field 묶음
	 */
	public void saveHashesExpiringAt(Map<String, Map<String, String>> hashes, long expireAtEpochSecond) {
		forEachChunk(hashes, chunk -> stringRedisTemplate.executePipelined(
			(RedisCallback<Object>)connection -> {
				chunk.forEach(entry -> {
					byte[] key = toBytes(entry.getKey());
					connection.hashCommands().hMSet(key, toRawMap(entry.getValue()));
					connection.keyCommands().expireAt(key, expireAtEpochSecond);
				});
				return null;
			}));
	}

	public Set<String> hashFieldNames(String key) {
		HashOperations<String, String, String> hashOperations = stringRedisTemplate.opsForHash();
		return hashOperations.keys(key);
	}

	public void deleteHashFields(String key, Collection<String> fields) {
		if (fields.isEmpty()) {
			return;
		}
		stringRedisTemplate.opsForHash().delete(key, fields.toArray());
	}

	/**
	 * 패턴에 맞는 키를 모은다. 운영 Redis를 멈추게 하는 {@code KEYS} 대신 커서로 훑는다.
	 */
	public Set<String> scanKeys(String pattern) {
		ScanOptions options = ScanOptions.scanOptions()
			.match(pattern)
			.count(trainCacheProperties.getPipelineSize())
			.build();

		Set<String> keys = new HashSet<>();
		try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				keys.add(cursor.next());
			}
		}
		return keys;
	}

	public void deleteKeys(Collection<String> keys) {
		if (keys.isEmpty()) {
			return;
		}
		stringRedisTemplate.delete(keys);
	}

	/**
	 * Map을 설정된 크기로 잘라 순서대로 넘긴다.
	 */
	private <V> void forEachChunk(Map<String, V> source, Consumer<List<Map.Entry<String, V>>> action) {
		if (source.isEmpty()) {
			return;
		}
		List<Map.Entry<String, V>> entries = new ArrayList<>(source.entrySet());
		int chunkSize = trainCacheProperties.getPipelineSize();

		for (int start = 0; start < entries.size(); start += chunkSize) {
			action.accept(entries.subList(start, Math.min(start + chunkSize, entries.size())));
		}
	}

	private static Map<String, String> toMap(List<Map.Entry<String, String>> entries) {
		Map<String, String> map = new LinkedHashMap<>();
		entries.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
		return map;
	}

	/**
	 * 파이프라인 콜백 안에서는 RedisTemplate의 serializer가 적용되지 않아 직접 바이트로 바꿔야 한다.
	 */
	private static Map<byte[], byte[]> toRawMap(Map<String, String> fields) {
		Map<byte[], byte[]> raw = new LinkedHashMap<>();
		fields.forEach((field, value) -> raw.put(toBytes(field), toBytes(value)));
		return raw;
	}

	private static byte[] toBytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
