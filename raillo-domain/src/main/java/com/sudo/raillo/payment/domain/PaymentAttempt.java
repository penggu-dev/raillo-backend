package com.sudo.raillo.payment.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.sudo.raillo.global.exception.DomainException;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "payment_attempt", indexes = {
	@Index(name = "idx_payment_attempt_payment_id", columnList = "payment_id"),
	@Index(name = "idx_payment_attempt_status_type", columnList = "status,attempt_type"),
	@Index(name = "uk_payment_attempt_attempt_id", columnList = "attempt_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class PaymentAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "payment_attempt_id")
	private Long id;

	@Column(name = "payment_id", nullable = false)
	@Comment("payment.payment_id를 참조 (FK 제약은 걸지 않음)")
	private Long paymentId;

	@Column(name = "attempt_id", nullable = false, length = 64)
	@Comment("외부 idempotency key. DB PK와 별개로 두는 이유는 도메인 문서 참고: docs/payment/README.md")
	private String attemptId;

	@Enumerated(EnumType.STRING)
	@Column(name = "attempt_type", nullable = false, length = 20)
	private PaymentAttemptType attemptType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentAttemptStatus status;

	@Column(name = "payment_key")
	private String paymentKey;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_message", length = 500)
	private String errorMessage;

	@Column(name = "processing_owner", length = 100)
	@Comment("Recovery Worker 동시 처리 방지용 소유자 식별자")
	private String processingOwner;

	@Column(name = "processing_lease_until")
	private LocalDateTime processingLeaseUntil;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public static PaymentAttempt startApproval(Long paymentId, String attemptId, String paymentKey) {
		PaymentAttempt attempt = new PaymentAttempt();
		attempt.paymentId = paymentId;
		attempt.attemptId = attemptId;
		attempt.paymentKey = paymentKey;
		attempt.attemptType = PaymentAttemptType.APPROVAL;
		attempt.status = PaymentAttemptStatus.IN_PROGRESS;
		return attempt;
	}

	public void markSucceeded() {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.SUCCEEDED;
	}

	public void markFailed(String errorCode, String errorMessage) {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.FAILED;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}

	/**
	 * 요청이 게이트웨이에 도달하지 않은 것이 확정인 실패로 표시한다.
	 *
	 * <p>{@code FAILED}를 쓰지 않는 이유는 attemptId가 paymentKey에서 결정적으로 파생되기 때문이다.
	 * {@code FAILED}로 두면 승인 재요청이 {@code PAYMENT_ATTEMPT_ALREADY_FAILED}로 거절되어 그 paymentKey로는
	 * 다시 승인할 수 없다. 호출이 나가지 않았으므로 사용자가 다시 요청해도 중복 처리 위험이 없다.</p>
	 */
	public void markNotSent(String errorCode, String errorMessage) {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.NOT_SENT;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}

	/** 전송되지 않은 attempt를 다시 진행 중으로 되돌린다. 이전 실패 정보는 지운다. */
	public void reopen() {
		if (this.status != PaymentAttemptStatus.NOT_SENT) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.IN_PROGRESS;
		this.errorCode = null;
		this.errorMessage = null;
	}

	/**
	 * Toss에서 승인돼 돈이 나갔지만 자동으로 예매를 확정하면 안 되는 attempt로 표시한다.
	 * errorCode는 {@code REVIEW_SEAT_LOST}, {@code REVIEW_DEPARTED}, {@code REVIEW_RESULT_MISMATCH} 중 하나다.
	 */
	public void markReviewRequired(String errorCode, String errorMessage) {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.REVIEW_REQUIRED;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}
}
