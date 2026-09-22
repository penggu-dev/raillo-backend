package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.domain.PaymentOutboxType;

public interface OutboxEventProcessor {

	boolean supports(PaymentOutboxType type);

	void process(String payload);
}
