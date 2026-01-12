package com.sudo.raillo.global.event.application.dto;

import java.math.BigDecimal;

public record PaymentCompletedEvent(
	Long orderId,
	Long paymentId,
	String PaymentKey,
	BigDecimal amount
) {
}


