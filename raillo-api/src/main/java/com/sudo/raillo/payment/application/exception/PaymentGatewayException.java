package com.sudo.raillo.payment.application.exception;

import com.sudo.raillo.global.exception.ExternalApiException;

public class PaymentGatewayException extends ExternalApiException {

	protected PaymentGatewayException(
		int httpStatus,
		String errorCode,
		String errorMessage,
		String provider,
		String errorType
	) {
		super(httpStatus, errorCode, errorMessage, provider, errorType);
	}

	/**
	 * 4xx 응답은 요청 거절이 확정된 것으로 본다. 5xx는 게이트웨이가 승인한 뒤 응답에
	 * 실패했을 수 있으므로 결과 불명으로 분류한다.
	 */
	public boolean isDefinitiveFailure() {
		return getHttpStatus() >= 400 && getHttpStatus() < 500;
	}
}
