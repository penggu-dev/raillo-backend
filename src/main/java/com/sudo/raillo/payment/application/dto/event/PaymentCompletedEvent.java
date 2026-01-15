package com.sudo.raillo.payment.application.dto.event;

import com.sudo.raillo.global.event.domain.DomainEvent;

@DomainEvent
public record PaymentCompletedEvent(
	Long orderId
) {
}
