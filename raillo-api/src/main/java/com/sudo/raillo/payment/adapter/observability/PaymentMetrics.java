package com.sudo.raillo.payment.adapter.observability;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class PaymentMetrics {

	private final MeterRegistry meterRegistry;
	private final Counter prepareCounter;
	private final Counter confirmSuccessCounter;
	private final Counter outboxFailedCounter;
	private final Counter cleanupFailureCounter;

	public PaymentMetrics(MeterRegistry meterRegistry, PaymentOutboxRepository paymentOutboxRepository) {
		this.meterRegistry = meterRegistry;

		this.prepareCounter = Counter.builder("payment_prepare_total")
			.description("결제 준비 성공 건수")
			.register(meterRegistry);

		this.confirmSuccessCounter = Counter.builder("payment_confirm_success_total")
			.description("결제 승인 성공 건수")
			.register(meterRegistry);

		this.outboxFailedCounter = Counter.builder("payment.outbox.failed")
			.description("Outbox 처리 최대 재시도 초과로 FAILED 전이된 건수")
			.register(meterRegistry);

		this.cleanupFailureCounter = Counter.builder("payment.cleanup.failure")
			.description("Outbox 처리 개별 실패 건수 (재시도 진입 포함)")
			.register(meterRegistry);

		Gauge.builder("payment.outbox.pending", paymentOutboxRepository, r -> r.countByStatus(PaymentOutboxStatus.PENDING))
			.description("현재 처리 대기 중인 outbox PENDING 건수")
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

	public void incrementOutboxFailed() {
		outboxFailedCounter.increment();
	}

	public void incrementCleanupFailure() {
		cleanupFailureCounter.increment();
	}
}
