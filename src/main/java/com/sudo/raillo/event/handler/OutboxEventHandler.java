package com.sudo.raillo.event.handler;

/**
 * Outbox 이벤트 핸들러 인터페이스
 * Strategy Pattern으로 이벤트 타입별 처리 로직 분리
 *
 * @param <T> Payload 타입
 */
public interface OutboxEventHandler<T> {

	/**
	 * 이 핸들러가 처리할 이벤트 타입
	 * @return "PaymentCompletedEvent", "OrderCreatedEvent" 등
	 */
	String getEventType();

	/**
	 * Payload 클래스 타입 (JSON 역직렬화용)
	 */
	Class<T> getPayloadClass();

	/**
	 * 이벤트 처리 로직
	 * @param payload 역직렬화된 이벤트 데이터
	 */
	void handle(T payload);
}
