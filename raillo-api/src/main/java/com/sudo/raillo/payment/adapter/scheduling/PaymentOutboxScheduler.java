package com.sudo.raillo.payment.adapter.scheduling;

import com.sudo.raillo.payment.application.outbox.PaymentOutboxWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raillo.payment.outbox.enabled", havingValue = "true")
public class PaymentOutboxScheduler {
	private final PaymentOutboxWorker worker;

	@Scheduled(fixedDelayString = "${raillo.payment.outbox.polling-interval}")
	public void poll() {
		worker.poll();
	}
}
