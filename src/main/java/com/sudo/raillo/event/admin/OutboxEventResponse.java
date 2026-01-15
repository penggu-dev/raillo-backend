package com.sudo.raillo.event.admin;

import java.time.LocalDateTime;

import com.sudo.raillo.event.domain.OutboxEvent;
import com.sudo.raillo.event.domain.OutboxStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbox 이벤트 응답 DTO
 */
@Schema(description = "이벤트 응답 DTO")
public record OutboxEventResponse(
	Long id,
	String aggregateType,
	Long aggregateId,
	String eventType,
	String payload,
	OutboxStatus status,
	int retryCount,
	int maxRetries,
	String lastError,
	String processedBy,
	LocalDateTime processingStartedAt,
	LocalDateTime createdAt,
	LocalDateTime completedAt
) {

	public static OutboxEventResponse from(OutboxEvent event) {
		return new OutboxEventResponse(
			event.getId(),
			event.getAggregateType(),
			event.getAggregateId(),
			event.getEventType(),
			event.getPayload(),
			event.getStatus(),
			event.getRetryCount(),
			event.getMaxRetries(),
			event.getLastError(),
			event.getProcessedBy(),
			event.getProcessingStartedAt(),
			event.getCreatedAt(),
			event.getCompletedAt()
		);
	}
}
