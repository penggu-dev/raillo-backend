package com.sudo.raillo.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentOutboxType - dedup 키 파생")
class PaymentOutboxTypeTest {

	@Test
	@DisplayName("예매 확정 타입의 dedup 키는 payment:{id}:booking-confirmed 형식이다")
	void bookingConfirmed_deduplicationKey() {
		// given & when
		String key = PaymentOutboxType.BOOKING_CONFIRMED.deduplicationKey(100L);

		// then
		assertThat(key).isEqualTo("payment:100:booking-confirmed");
	}

	@Test
	@DisplayName("좌석 해제 타입의 dedup 키는 booking:{id}:seat-release 형식이다")
	void bookingSeatRelease_deduplicationKey() {
		// given & when
		String key = PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(77L);

		// then
		assertThat(key).isEqualTo("booking:77:seat-release");
	}

	@Test
	@DisplayName("모든 타입이 집합체와 이벤트를 구분하는 dedup 키를 만든다")
	void everyType_producesDistinctKeyForSameAggregateId() {
		// given - 같은 aggregateId로 모든 타입의 키를 만든다
		long aggregateId = 1L;

		// when
		var keys = Arrays.stream(PaymentOutboxType.values())
			.map(type -> type.deduplicationKey(aggregateId))
			.toList();

		// then
		assertThat(keys)
			.doesNotHaveDuplicates()
			.allSatisfy(key -> assertThat(key).matches("[a-z-]+:1:[a-z-]+"));
	}
}
