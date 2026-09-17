package com.sudo.raillo.booking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SeatOccupancyValue - 좌석 점유 값 형식")
class SeatOccupancyValueTest {

	@Test
	@DisplayName("임시 점유는 H 접두사와 예약 ID로 직렬화된다")
	void serializes_hold() {
		// given
		SeatOccupancyValue hold = SeatOccupancyValue.hold("RV1");

		// when
		String value = hold.serialize();

		// then
		assertThat(value).isEqualTo("H:RV1");
	}

	@Test
	@DisplayName("확정 판매는 B 접두사와 예매 ID로 직렬화된다")
	void serializes_sold() {
		// given
		SeatOccupancyValue sold = SeatOccupancyValue.sold("77");

		// when
		String value = sold.serialize();

		// then
		assertThat(value).isEqualTo("B:77");
	}

	@Test
	@DisplayName("직렬화한 값을 다시 읽으면 같은 점유 정보가 된다")
	void round_trips() {
		// given
		SeatOccupancyValue original = SeatOccupancyValue.hold("RV20260917120000ABC123");

		// when
		SeatOccupancyValue parsed = SeatOccupancyValue.parse(original.serialize());

		// then
		assertThat(parsed).isEqualTo(original);
		assertThat(parsed.isHold()).isTrue();
		assertThat(parsed.isSold()).isFalse();
	}

	@Test
	@DisplayName("자기 예약이 점유한 field인지 예약 ID로 판별한다")
	void identifies_owner() {
		// given
		SeatOccupancyValue hold = SeatOccupancyValue.parse("H:RV1");
		SeatOccupancyValue sold = SeatOccupancyValue.parse("B:RV1");

		// when

		// then
		assertThat(hold.isHeldBy("RV1")).isTrue();
		assertThat(hold.isHeldBy("RV2")).isFalse();
		assertThat(sold.isHeldBy("RV1")).isFalse();
	}

	@Test
	@DisplayName("접두사나 ID가 없는 값은 거부한다")
	void rejects_malformed_value() {
		// given

		// when

		// then
		assertThatThrownBy(() -> SeatOccupancyValue.parse("X:1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeatOccupancyValue.parse("H:")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeatOccupancyValue.parse("RV1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeatOccupancyValue.parse(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeatOccupancyValue.hold(" ")).isInstanceOf(IllegalArgumentException.class);
	}
}
