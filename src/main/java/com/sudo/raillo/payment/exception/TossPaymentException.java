package com.sudo.raillo.payment.exception;

import com.sudo.raillo.global.exception.error.ExternalApiException;

public class TossPaymentException extends ExternalApiException {

	public TossPaymentException(int httpStatus, String errorCode, String errorMessage) {
		super(httpStatus, errorCode, errorMessage, "TOSS", "PAYMENT");
	}
}
