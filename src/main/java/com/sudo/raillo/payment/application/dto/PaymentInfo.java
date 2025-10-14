package com.sudo.raillo.payment.application.dto;

import java.math.BigDecimal;

import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.domain.type.PaymentMethod;

public record PaymentInfo(BigDecimal amount, PaymentMethod paymentMethod, PaymentStatus paymentStatus) {
}
