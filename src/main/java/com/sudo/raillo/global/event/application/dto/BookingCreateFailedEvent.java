package com.sudo.raillo.global.event.application.dto;

public record BookingCreateFailedEvent(
	Long orderId,
	Long paymentId,
	String PaymentKey
) {
}
