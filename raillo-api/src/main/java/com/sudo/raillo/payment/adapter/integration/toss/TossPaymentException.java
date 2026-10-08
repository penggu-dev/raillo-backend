package com.sudo.raillo.payment.adapter.integration.toss;

import com.sudo.raillo.payment.application.exception.DeliveryPhase;
import com.sudo.raillo.payment.application.exception.PaymentGatewayException;

public class TossPaymentException extends PaymentGatewayException {

	public TossPaymentException(int httpStatus, String errorCode, String errorMessage) {
		super(httpStatus, errorCode, errorMessage, "TOSS", "PAYMENT", DeliveryPhase.ANSWERED);
	}

}
