package com.sudo.raillo.payment.adapter.observability;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.recovery.RecoveryProperties;
import com.sudo.raillo.payment.application.required.OutboxMetrics;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.application.required.RecoveryMetrics;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class PaymentMetrics implements OutboxMetrics, RecoveryMetrics {

	/** 수동 확인 대기 알림이 보는 창. 상태에 해소 표식이 없어 전체 행 게이지로는 알림을 걸 수 없다. */
	private static final Duration REVIEW_REQUIRED_RECENT_WINDOW = Duration.ofHours(24);

	private final MeterRegistry meterRegistry;
	private final Counter prepareCounter;
	private final Counter confirmSuccessCounter;
	private final Counter outboxFailedCounter;
	private final Counter cleanupFailureCounter;
	private final Counter outboxNonRetryableCounter;
	private final Counter recoveredCounter;
	private final Counter retryScheduledCounter;
	private final Counter processedCounter;
	private final Timer pollTimer;

	public PaymentMetrics(
		MeterRegistry meterRegistry, PaymentOutboxRepository paymentOutboxRepository,
		PaymentAttemptRepository paymentAttemptRepository, RecoveryProperties recoveryProperties
	) {
		this.meterRegistry = meterRegistry;

		this.prepareCounter = Counter.builder("payment_prepare_total")
			.description("결제 준비 성공 건수")
			.register(meterRegistry);

		this.confirmSuccessCounter = Counter.builder("payment_confirm_success_total")
			.description("결제 승인 성공 건수")
			.register(meterRegistry);

		this.outboxFailedCounter = Counter.builder("payment.outbox.failed")
			.description("Outbox 처리가 FAILED로 전이된 건수 (최대 재시도 초과 또는 재시도 불가)")
			.register(meterRegistry);

		this.outboxNonRetryableCounter = Counter.builder("payment.outbox.non_retryable")
			.description("재시도로 낫지 않는 원인이라 백오프 없이 FAILED 전이된 건수")
			.register(meterRegistry);

		this.cleanupFailureCounter = Counter.builder("payment.cleanup.failure")
			.description("Outbox 처리 개별 실패 건수 (재시도 진입 포함)")
			.register(meterRegistry);

		this.recoveredCounter = Counter.builder("payment.recovery.recovered")
			.description("복구 Worker가 확정 재실행에 성공한 건수")
			.register(meterRegistry);

		this.retryScheduledCounter = Counter.builder("payment.outbox.retry_scheduled")
			.description("백오프 후 재시도로 돌린 outbox 행 수. FAILED 전이는 세지 않는다")
			.register(meterRegistry);

		this.processedCounter = Counter.builder("payment.outbox.processed")
			.description("Outbox Worker가 성공 처리한 행 수")
			.register(meterRegistry);

		// 빈 poll은 기록하지 않는다. 함께 재면 평균이 0에 가까워져 배치 크기를 비교할 수 없다
		this.pollTimer = Timer.builder("payment.outbox.poll")
			.description("처리할 행이 있었던 poll 한 번의 실행 시간")
			.register(meterRegistry);

		Gauge.builder("payment.outbox.pending", paymentOutboxRepository, r -> r.countByStatus(PaymentOutboxStatus.PENDING))
			.description("현재 처리 대기 중인 outbox PENDING 건수")
			.register(meterRegistry);

		Gauge.builder("payment.outbox.oldest_pending_seconds", paymentOutboxRepository,
				PaymentMetrics::oldestPendingAgeSeconds)
			.description("가장 오래된 PENDING 행의 나이. 적체의 최고 대기 시간")
			.register(meterRegistry);

		// 카운터는 프로세스를 재시작하면 누적이 사라져 과거 사고가 보이지 않는다. 사람 개입을 기다리는 행은 게이지로 본다
		Gauge.builder("payment.outbox.failed_rows", paymentOutboxRepository,
				r -> r.countByStatus(PaymentOutboxStatus.FAILED))
			.description("FAILED로 종착해 사람 개입을 기다리는 outbox 행 수")
			.register(meterRegistry);

		Gauge.builder("payment.attempt.review_required_rows", paymentAttemptRepository,
				r -> r.countByStatus(PaymentAttemptStatus.REVIEW_REQUIRED))
			.description("수동 확인으로 남아 사람 개입을 기다리는 결제 시도 수")
			.register(meterRegistry);

		Gauge.builder("payment.attempt.review_required_recent_rows", paymentAttemptRepository,
				r -> r.countByStatusUpdatedAfter(PaymentAttemptStatus.REVIEW_REQUIRED,
					LocalDateTime.now().minus(REVIEW_REQUIRED_RECENT_WINDOW)))
			.description("최근 24시간 안에 수동 확인으로 바뀌어 사람 개입을 기다리는 결제 시도 수")
			.register(meterRegistry);

		Gauge.builder("payment.attempt.stale_in_progress_rows", paymentAttemptRepository,
				r -> r.countStaleInProgress(PaymentAttemptType.APPROVAL,
					LocalDateTime.now().minus(recoveryProperties.staleAfter())))
			.description("복구 임계값을 넘겨 진행 중으로 남은 결제 시도 수")
			.register(meterRegistry);
	}

	public void incrementPrepare() {
		prepareCounter.increment();
	}

	public void incrementConfirmSuccess() {
		confirmSuccessCounter.increment();
	}

	public void incrementConfirmFailure(String reason, int httpStatus, String errorCode) {
		Counter.builder("payment_confirm_failure_total")
			.description("결제 승인 실패 건수")
			.tag("reason", reason)
			.tag("http_status", String.valueOf(httpStatus))
			.tag("error_code", errorCode)
			.register(meterRegistry)
			.increment();
	}

	@Override
	public void incrementOutboxFailed() {
		outboxFailedCounter.increment();
	}

	@Override
	public void incrementCleanupFailure() {
		cleanupFailureCounter.increment();
	}

	@Override
	public void incrementOutboxNonRetryable() {
		outboxNonRetryableCounter.increment();
	}

	@Override
	public void incrementDecision(String decision) {
		Counter.builder("payment.recovery.decision")
			.description("복구 Worker의 판정별 건수")
			.tag("decision", decision)
			.register(meterRegistry)
			.increment();
	}

	@Override
	public void incrementRecovered() {
		recoveredCounter.increment();
	}

	@Override
	public void incrementRetryScheduled() {
		retryScheduledCounter.increment();
	}

	@Override
	public void incrementProcessed() {
		processedCounter.increment();
	}

	@Override
	public void recordPollDuration(Duration elapsed) {
		pollTimer.record(elapsed);
	}

	/** PENDING 행이 없으면 0이다. 적체가 없다는 뜻이므로 게이지를 비우지 않는다. */
	private static double oldestPendingAgeSeconds(PaymentOutboxRepository repository) {
		return repository.findOldestPendingCreatedAt()
			.map(createdAt -> (double)Duration.between(createdAt, LocalDateTime.now()).toSeconds())
			.orElse(0.0);
	}
}
