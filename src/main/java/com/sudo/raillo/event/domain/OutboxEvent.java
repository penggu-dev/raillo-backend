package com.sudo.raillo.event.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox_event", indexes = {
	@Index(name = "idx_status_created", columnList = "status, created_at"),
	@Index(name = "idx_status_processing_started", columnList = "status, processing_started_at"),
	@Index(name = "idx_aggregate", columnList = "aggregate_type, aggregate_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	private static final int DEFAULT_MAX_RETRIES = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String aggregateType;

	@Column(nullable = false)
	private Long aggregateId;

	@Column(nullable = false, length = 100)
	private String eventType;

	@Column(nullable = false, columnDefinition = "JSON")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxStatus status;

	@Column(nullable = false)
	private int retryCount;

	@Column(nullable = false)
	private int maxRetries;

	@Column(columnDefinition = "TEXT")
	private String lastError;

	@Column(length = 100)
	private String processedBy;

	private LocalDateTime processingStartedAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private LocalDateTime completedAt;

	// === 팩토리 메서드 ===

	public static OutboxEvent create(String aggregateType, Long aggregateId,
		String eventType, String payload) {
		OutboxEvent event = new OutboxEvent();
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.payload = payload;
		event.status = OutboxStatus.PENDING;
		event.retryCount = 0;
		event.maxRetries = DEFAULT_MAX_RETRIES;
		event.createdAt = LocalDateTime.now();
		return event;
	}

	// === 상태 전이 메서드 ===

	/**
	 * 즉시 처리 성공 (AFTER_COMMIT)
	 * PENDING → COMPLETED
	 */
	public void complete(String podId) {
		this.status = OutboxStatus.COMPLETED;
		this.processedBy = podId;
		this.completedAt = LocalDateTime.now();
	}

	/**
	 * 처리 실패
	 * PENDING/PROCESSING → FAILED or DEAD
	 */
	public void fail(String errorMessage) {
		this.retryCount++;
		this.lastError = truncateError(errorMessage);
		this.status = (this.retryCount >= this.maxRetries)
			? OutboxStatus.DEAD
			: OutboxStatus.FAILED;
	}

	/**
	 * 배치 처리 시작 (선점)
	 * FAILED/PENDING/PROCESSING → PROCESSING
	 */
	public void startProcessing(String podId) {
		this.status = OutboxStatus.PROCESSING;
		this.processedBy = podId;
		this.processingStartedAt = LocalDateTime.now();
	}

	/**
	 * 수동 재시도 (DEAD → PENDING)
	 */
	public void resetForRetry() {
		this.status = OutboxStatus.PENDING;
		this.lastError = null;
		this.processedBy = null;
		this.processingStartedAt = null;
	}

	// === 조회 메서드 ===

	public boolean isDead() {
		return this.status == OutboxStatus.DEAD;
	}

	public boolean isPending() {
		return this.status == OutboxStatus.PENDING;
	}

	// === private 메서드 ===

	private String truncateError(String errorMessage) {
		if (errorMessage == null) {
			return null;
		}
		return errorMessage.length() > 1000
			? errorMessage.substring(0, 1000)
			: errorMessage;
	}
}
