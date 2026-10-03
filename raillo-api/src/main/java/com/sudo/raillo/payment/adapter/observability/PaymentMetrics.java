package com.sudo.raillo.payment.adapter.observability;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.OutboxMetrics;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class PaymentMetrics implements OutboxMetrics {

	private final MeterRegistry meterRegistry;
	private final Counter prepareCounter;
	private final Counter confirmSuccessCounter;
	private final Counter outboxFailedCounter;
	private final Counter cleanupFailureCounter;
	private final Counter outboxNonRetryableCounter;

	public PaymentMetrics(MeterRegistry meterRegistry, PaymentOutboxRepository paymentOutboxRepository) {
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

		Gauge.builder("payment.outbox.pending", paymentOutboxRepository, r -> r.countByStatus(PaymentOutboxStatus.PENDING))
			.description("현재 처리 대기 중인 outbox PENDING 건수")
			.register(meterRegistry);

		// 카운터는 프로세스를 재시작하면 누적이 사라져 과거 사고가 보이지 않는다. 사람 개입을 기다리는 행은 게이지로 본다
		Gauge.builder("payment.outbox.failed_rows", paymentOutboxRepository,
				r -> r.countByStatus(PaymentOutboxStatus.FAILED))
			.description("FAILED로 종착해 사람 개입을 기다리는 outbox 행 수")
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
}
