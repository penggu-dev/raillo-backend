package com.sudo.raillo.payment.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.domain.type.PaymentMethod;

public record PaymentConfirmResponse(
	Long paymentId,
	String orderId,
	String paymentKey,
	BigDecimal amount,
	PaymentMethod paymentMethod,
	PaymentStatus paymentStatus,
	LocalDateTime paidAt
) {
	public static PaymentConfirmResponse from(Payment payment) {
		return new PaymentConfirmResponse(
			payment.getId(),
			payment.getOrderId(),
			payment.getPaymentKey(),
			payment.getAmount(),
			payment.getPaymentMethod(),
			payment.getPaymentStatus(),
			payment.getPaidAt()
		);
	}
}
