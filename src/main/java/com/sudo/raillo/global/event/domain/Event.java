package com.sudo.raillo.global.event.domain;


import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event", indexes = {
	@Index(name = "idx_event_status_created_at", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

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
	private EventStatus status;

	private int retryCount;

	private LocalDateTime completedAt;

	public static Event create(
		String aggregateType,
		Long aggregateId,
		String eventType,
		String payload
	) {
		Event event = new Event();
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.payload = payload;
		event.status = EventStatus.PROGRESS;
		event.retryCount = 0;
		return event;
	}

	public void complete() {
		validateCompletable();
		this.status = EventStatus.COMPLETED;
		this.completedAt = LocalDateTime.now();
	}

	public void fail() {
		validateRetryable();
		this.retryCount++;

		if (this.retryCount >= 3) {
			this.status = EventStatus.FAILED;
		}
	}

	private void validateCompletable() {
		if(this.status != EventStatus.PROGRESS) {
			throw new DomainException(EventError.EVENT_NOT_COMPLETABLE);
		}
	}

	private void validateRetryable() {
		if (this.status != EventStatus.RETRY) {
			throw new DomainException(EventError.EVENT_NOT_RETRYABLE);
		}
	}
}
