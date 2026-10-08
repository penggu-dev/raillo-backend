package com.sudo.raillo.payment.adapter.integration.toss;

import com.sudo.raillo.payment.application.exception.DeliveryPhase;
import com.sudo.raillo.payment.application.exception.PaymentGatewayException;

public class TossPaymentException extends PaymentGatewayException {

	/** 토스가 HTTP 응답을 돌려준 실패. */
	public TossPaymentException(int httpStatus, String errorCode, String errorMessage) {
		this(httpStatus, errorCode, errorMessage, DeliveryPhase.ANSWERED);
	}

	public TossPaymentException(int httpStatus, String errorCode, String errorMessage, DeliveryPhase phase) {
		super(httpStatus, errorCode, errorMessage, "TOSS", "PAYMENT", phase);
	}

}
