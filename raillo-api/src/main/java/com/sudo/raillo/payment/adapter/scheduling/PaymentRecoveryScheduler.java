package com.sudo.raillo.payment.adapter.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.recovery.PaymentRecoveryWorker;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "raillo.payment.recovery.enabled", havingValue = "true")
public class PaymentRecoveryScheduler {

	private final PaymentRecoveryWorker worker;

	@Scheduled(fixedDelayString = "${raillo.payment.recovery.polling-interval}")
	public void poll() {
		worker.poll();
	}
}
