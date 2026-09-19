package com.sudo.raillo.batch.train.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.TrainCarCacheValue;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;

/**
 * 저장 형식은 raillo-api가 읽을 모듈 간 계약이다. 형식이 바뀌면 이 테스트가 먼저 깨져야 한다.
 */
@DisplayName("TrainCacheJsonConverter - 기준정보 저장 형식")
class TrainCacheJsonConverterTest {

	private final TrainCacheJsonConverter converter = new TrainCacheJsonConverter();

	@Test
	@DisplayName("좌석 값은 Java 타입 정보 없는 평문 JSON이 된다")
	void writesSeatAsPlainJson() {
		// given
		SeatCacheValue seat = new SeatCacheValue(231L, 3, CarType.STANDARD, 12, "A", SeatType.WINDOW);

		// when
		String json = converter.toJson(seat);

		// then
		assertThat(json).isEqualTo(
			"{\"trainCarId\":231,\"carNumber\":3,\"carType\":\"STANDARD\","
				+ "\"seatRow\":12,\"seatColumn\":\"A\",\"seatType\":\"WINDOW\"}");
	}

	@Test
	@DisplayName("값 어디에도 @class 타입 메타데이터가 붙지 않는다")
	void neverWritesTypeMetadata() {
		// given - 타입 정보가 박히면 읽는 쪽이 같은 FQCN을 가져야 해서 모듈이 묶인다
		Object[] values = {
			new SeatCacheValue(231L, 3, CarType.STANDARD, 12, "A", SeatType.WINDOW),
			new TrainCarCacheValue(7L, 3, CarType.FIRST_CLASS, 15, 60, "2+2"),
			new ScheduleStopCacheValue(9001L, 1, 1L, "서울", null, LocalTime.of(6, 0))
		};

		// when

		// then
		for (Object value : values) {
			assertThat(converter.toJson(value))
				.doesNotContain("@class")
				.doesNotContain("com.sudo.raillo");
		}
	}

	@Test
	@DisplayName("시각은 숫자 배열이 아니라 고정 패턴 문자열로 저장된다")
	void writesTimesAsFixedPatternStrings() {
		// given
		ScheduleInfoCacheValue info = new ScheduleInfoCacheValue(
			1001L,
			LocalDate.of(2026, 1, 1),
			LocalTime.of(6, 0),
			LocalTime.of(8, 52),
			OperationStatus.ACTIVE,
			0, 7L, 101, "KTX", 1L, 5L);

		// when
		String json = converter.toJson(info);

		// then
		assertThat(json)
			.contains("\"operationDate\":\"2026-01-01\"")
			.contains("\"departureTime\":\"06:00:00\"")
			.contains("\"arrivalTime\":\"08:52:00\"")
			.contains("\"operationStatus\":\"ACTIVE\"");
	}

	@Test
	@DisplayName("기점의 도착 시각은 null로 저장된다")
	void keepsNullTimeAtTerminal() {
		// given - 기점은 도착한 적이 없고 종점은 출발하지 않는다
		ScheduleStopCacheValue origin = new ScheduleStopCacheValue(9001L, 1, 1L, "서울", null, LocalTime.of(6, 0));

		// when
		String json = converter.toJson(origin);

		// then
		assertThat(json).isEqualTo(
			"{\"stopId\":9001,\"stopOrder\":1,\"stationId\":1,\"stationName\":\"서울\","
				+ "\"arrivalTime\":null,\"departureTime\":\"06:00:00\"}");
	}
}
