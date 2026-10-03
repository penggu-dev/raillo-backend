package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.booking.cache.SeatOccupancyValue;

@DisplayName("SeatOccupancyResult - Lua 반환값 파싱")
class SeatOccupancyResultTest {

	@Test
	@DisplayName("성공 응답은 충돌 정보 없이 성공으로 파싱된다")
	void parses_success() {
		SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(List.of(1L));

		assertThat(result.success()).isTrue();
		assertThat(result.conflictSeatId()).isNull();
		assertThat(result.conflictType()).isNull();
	}

	@Test
	@DisplayName("충돌 응답은 좌석과 구간, 점유 유형으로 파싱된다")
	void parses_conflict() {
		SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(List.of(0L, "7001", 3L, "B"));

		assertThat(result.success()).isFalse();
		assertThat(result.conflictSeatId()).isEqualTo(7001L);
		assertThat(result.conflictSectionIndex()).isEqualTo(3);
		assertThat(result.conflictType()).isEqualTo(SeatOccupancyValue.Type.BOOKED);
		assertThat(result.isConflictWithBooking()).isTrue();
	}

	@Test
	@DisplayName("알 수 없는 점유 유형은 데이터 오염이므로 SeatOccupancyCorruptedException을 던진다")
	void throws_corrupted_for_unknown_type() {
		assertThatThrownBy(() -> SeatOccupancyResult.fromLuaResult(List.of(0L, "7001", 3L, "X")))
			.isInstanceOf(SeatOccupancyCorruptedException.class)
			.hasMessageContaining("알 수 없는 좌석 점유 값");
	}

	@Test
	@DisplayName("응답이 비어 있으면 계약 위반이므로 오염 예외가 아니다")
	void throws_plain_illegal_state_for_empty_response() {
		assertThatThrownBy(() -> SeatOccupancyResult.fromLuaResult(List.of()))
			.isInstanceOf(IllegalStateException.class)
			.isNotInstanceOf(SeatOccupancyCorruptedException.class);
	}

	@Test
	@DisplayName("충돌 응답의 항목이 모자라면 계약 위반이므로 오염 예외가 아니다")
	void throws_plain_illegal_state_for_short_conflict_response() {
		assertThatThrownBy(() -> SeatOccupancyResult.fromLuaResult(List.of(0L, "7001")))
			.isInstanceOf(IllegalStateException.class)
			.isNotInstanceOf(SeatOccupancyCorruptedException.class);
	}
}
