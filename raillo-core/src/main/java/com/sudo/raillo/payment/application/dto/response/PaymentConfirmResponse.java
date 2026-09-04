package com.sudo.raillo.payment.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;

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
			payment.getOrderCode(),
			payment.getPaymentKey(),
			payment.getAmount(),
			payment.getPaymentMethod(),
			payment.getPaymentStatus(),
			payment.getPaidAt()
		);
	}
}
