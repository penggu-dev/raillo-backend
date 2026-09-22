package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.domain.PaymentOutboxType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventDispatcher {

	private final List<OutboxEventProcessor> processors;

	public OutboxEventDispatcher(List<OutboxEventProcessor> processors) {
		this.processors = processors;
	}

	public List<PaymentOutboxType> supportedTypes() {
		return java.util.Arrays.stream(PaymentOutboxType.values())
			.filter(type -> processors.stream().anyMatch(processor -> processor.supports(type)))
			.toList();
	}

	public void dispatch(PaymentOutboxType type, String payload) {
		OutboxEventProcessor processor = processors.stream()
			.filter(p -> p.supports(type))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No OutboxEventProcessor registered for type: " + type));
		processor.process(payload);
	}
}
