package com.sudo.raillo.payment.domain;

/**
 * Outbox 항목의 종류. dedup 키의 집합체와 이벤트 이름을 종류마다 갖는다.
 *
 * <p>키 형식을 여기 한 곳에 둔다. 항목을 만드는 쪽마다 문자열을 이어 붙이면 종류와 어긋난 키를
 * 넣어도 컴파일이 통과한다.</p>
 */
public enum PaymentOutboxType {

	BOOKING_CONFIRMED("payment", "booking-confirmed"),
	BOOKING_SEAT_RELEASE_REQUIRED("booking", "seat-release");

	private final String aggregate;
	private final String event;

	PaymentOutboxType(String aggregate, String event) {
		this.aggregate = aggregate;
		this.event = event;
	}

	/**
	 * 재발행 시 소비자 측 중복 처리를 막는 키. 형식은 {@code {집합체}:{id}:{이벤트}}다.
	 *
	 * <p>{@code aggregateId}의 의미가 종류마다 다르다. {@link #BOOKING_CONFIRMED}는 payment ID,
	 * {@link #BOOKING_SEAT_RELEASE_REQUIRED}는 booking ID다.</p>
	 */
	public String deduplicationKey(long aggregateId) {
		return "%s:%d:%s".formatted(aggregate, aggregateId, event);
	}
}
