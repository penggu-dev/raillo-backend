package com.sudo.raillo.global.event.domain;

import java.time.LocalDateTime;

import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.event.exception.EventError;
import com.sudo.raillo.global.exception.error.DomainException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String aggregateType; // "Payment" 등

	@Column(nullable = false)
	private Long aggregateId; // PaymentId 등

	@Column(nullable = false)
	private String eventType;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String payload; // JSON 직렬화 이벤트 데이터 // 스냅샷용

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OutboxStatus status;

	private int retryCount;

	private LocalDateTime completedAt;

	public static OutboxEvent create(
		String aggregateType,
		Long aggregateId,
		String eventType,
		String payload
	) {
		OutboxEvent event = new OutboxEvent();
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.payload = payload;
		event.status = OutboxStatus.PENDING;
		event.retryCount = 0;
		return event;
	}

	public void complete() {
		validateCompletable();
		this.status = OutboxStatus.COMPLETED;
		this.completedAt = LocalDateTime.now();
	}

	public void fail() {
		validateRetryable();
		this.retryCount++;

		if (this.retryCount >= 3) {
			this.status = OutboxStatus.FAILED;
		}
	}

	private void validateCompletable() {
		if(this.status != OutboxStatus.PENDING) {
			throw new DomainException(EventError.EVENT_NOT_COMPLETABLE);
		}
	}

	private void validateRetryable() {
		if(this.status != OutboxStatus.PENDING) {
			throw new DomainException(EventError.EVENT_NOT_RETRYABLE);
		}
	}
}
