package com.sudo.raillo.train.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrainCacheKey - 기준정보 Redis 키 계약")
class TrainCacheKeyTest {

	@Nested
	@DisplayName("정적 데이터 키")
	class StaticKeys {

		@Test
		@DisplayName("좌석·객차·역 키는 train 접두사와 ID로 만들어진다")
		void buildsStaticKeys() {
			// given

			// when

			// then
			assertThat(TrainCacheKey.seat(12L)).isEqualTo("train:seat:12");
			assertThat(TrainCacheKey.trainCar(231L)).isEqualTo("train:traincar:231");
			assertThat(TrainCacheKey.station(1L)).isEqualTo("train:station:1");
		}

		@Test
		@DisplayName("운임은 단일 Hash에 담기므로 키가 하나다")
		void fareIsSingleHash() {
			// given

			// when
			String key = TrainCacheKey.fare();

			// then
			assertThat(key).isEqualTo("train:fare");
		}

		@Test
		@DisplayName("운임 field는 출발역과 도착역 순서로 만들어져 방향을 구분한다")
		void fareFieldKeepsDirection() {
			// given

			// when
			String seoulToBusan = TrainCacheKey.fareField(1L, 5L);
			String busanToSeoul = TrainCacheKey.fareField(5L, 1L);

			// then
			assertThat(seoulToBusan).isEqualTo("1:5");
			assertThat(busanToSeoul).isEqualTo("5:1");
			assertThat(seoulToBusan).isNotEqualTo(busanToSeoul);
		}
	}

	@Nested
	@DisplayName("일일 운행 데이터 키")
	class ScheduleKeys {

		@Test
		@DisplayName("운행 키는 좌석 Hold 키와 같은 hash tag를 써서 같은 slot에 놓인다")
		void usesSameHashTagAsSeatHold() {
			// given - 좌석 Hold 키 형식: {schedule:1001}:seat:12:hold:pending_abc
			long trainScheduleId = 1001L;

			// when
			String info = TrainCacheKey.scheduleInfo(trainScheduleId);
			String stops = TrainCacheKey.scheduleStops(trainScheduleId);

			// then
			assertThat(info).isEqualTo("{schedule:1001}:info");
			assertThat(stops).isEqualTo("{schedule:1001}:stops");
			assertThat(info).startsWith("{schedule:1001}:");
			assertThat(stops).startsWith("{schedule:1001}:");
		}

		@Test
		@DisplayName("정차역은 역 ID와 정차역 ID 두 방향의 field를 가진다")
		void stopHasTwoFieldDirections() {
			// given

			// when
			String byStation = TrainCacheKey.stopFieldByStation(1L);
			String byStopId = TrainCacheKey.stopFieldByStopId(9001L);

			// then
			assertThat(byStation).isEqualTo("st:1");
			assertThat(byStopId).isEqualTo("id:9001");
			assertThat(byStation).isNotEqualTo(byStopId);
		}
	}

	@Nested
	@DisplayName("만료 시각")
	class ExpireAt {

		@Test
		@DisplayName("운행일 기준 2일 뒤 한국 시간 자정으로 계산한다")
		void calculatesFromOperationDate() {
			// given
			LocalDate operationDate = LocalDate.of(2026, 1, 1);

			// when
			long expireAt = TrainCacheKey.expireAtEpochSecond(operationDate);

			// then - 2026-01-03 00:00 KST = 2026-01-02 15:00 UTC
			assertThat(expireAt).isEqualTo(Instant.parse("2026-01-02T15:00:00Z").getEpochSecond());
		}

		@Test
		@DisplayName("적재 시점과 무관하게 운행일로만 결정된다")
		void dependsOnlyOnOperationDate() {
			// given - 월간 Job은 한 달치를 미리 만들기 때문에 적재 시점 기준이면 안 된다
			LocalDate operationDate = LocalDate.of(2026, 3, 15);

			// when
			long first = TrainCacheKey.expireAtEpochSecond(operationDate);
			long second = TrainCacheKey.expireAtEpochSecond(operationDate);

			// then
			assertThat(first).isEqualTo(second)
				.isEqualTo(Instant.parse("2026-03-16T15:00:00Z").getEpochSecond());
		}

		@Test
		@DisplayName("유지 일수를 직접 지정할 수 있다")
		void acceptsCustomRetentionDays() {
			// given
			LocalDate operationDate = LocalDate.of(2026, 1, 1);

			// when
			long expireAt = TrainCacheKey.expireAtEpochSecond(operationDate, 5);

			// then - 2026-01-06 00:00 KST
			assertThat(expireAt).isEqualTo(Instant.parse("2026-01-05T15:00:00Z").getEpochSecond());
		}
	}

	@Nested
	@DisplayName("오래된 키 정리 지원")
	class Prune {

		@Test
		@DisplayName("SCAN 패턴은 각 정적 키의 접두사에 와일드카드를 붙인 형태다")
		void providesScanPatterns() {
			// given

			// when

			// then
			assertThat(TrainCacheKey.seatKeyPattern()).isEqualTo("train:seat:*");
			assertThat(TrainCacheKey.trainCarKeyPattern()).isEqualTo("train:traincar:*");
			assertThat(TrainCacheKey.stationKeyPattern()).isEqualTo("train:station:*");
		}

		@Test
		@DisplayName("키에서 ID를 되꺼낼 수 있다")
		void extractsIdFromKey() {
			// given

			// when

			// then
			assertThat(TrainCacheKey.idFromKey(TrainCacheKey.seat(12L))).isEqualTo(12L);
			assertThat(TrainCacheKey.idFromKey(TrainCacheKey.trainCar(231L))).isEqualTo(231L);
			assertThat(TrainCacheKey.idFromKey(TrainCacheKey.station(7L))).isEqualTo(7L);
		}

		@Test
		@DisplayName("ID가 없는 키는 거부한다")
		void rejectsKeyWithoutId() {
			// given

			// when

			// then
			assertThatThrownBy(() -> TrainCacheKey.idFromKey("train:fare"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> TrainCacheKey.idFromKey("train:seat:"))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
