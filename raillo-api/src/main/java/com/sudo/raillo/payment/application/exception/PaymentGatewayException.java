package com.sudo.raillo.payment.application.exception;

import com.sudo.raillo.global.exception.ExternalApiException;

public class PaymentGatewayException extends ExternalApiException {

	private final DeliveryPhase deliveryPhase;

	protected PaymentGatewayException(
		int httpStatus,
		String errorCode,
		String errorMessage,
		String provider,
		String errorType,
		DeliveryPhase deliveryPhase
	) {
		super(httpStatus, errorCode, errorMessage, provider, errorType);
		this.deliveryPhase = deliveryPhase;
	}

	/**
	 * 요청이 게이트웨이에 도달하지 않은 것이 확정인지 확인한다. 같은 요청을 다시 보내도 중복 처리 위험이 없다.
	 */
	public boolean isNotReached() {
		return deliveryPhase == DeliveryPhase.NOT_REACHED;
	}

	/**
	 * 게이트웨이가 처리했는지 알 수 없는지 확인한다. 요청이 나간 뒤 결과를 받지 못했거나, 5xx로 응답한 경우다.
	 */
	public boolean isOutcomeUnknown() {
		return deliveryPhase == DeliveryPhase.NO_RESPONSE
			|| (deliveryPhase == DeliveryPhase.ANSWERED && getHttpStatus() >= 500);
	}

	/**
	 * 게이트웨이가 요청을 확정 거절했는지 확인한다. 4xx 응답이 여기에 해당한다. 5xx는 승인한 뒤 응답에
	 * 실패했을 수 있으므로 결과 불명으로 분류한다.
	 */
	public boolean isDefinitiveFailure() {
		return deliveryPhase == DeliveryPhase.ANSWERED
			&& getHttpStatus() >= 400 && getHttpStatus() < 500;
	}

	/**
	 * 조회한 결제가 게이트웨이에 존재하지 않는 상태로 확정된 응답인지 확인한다. HTTP 404 계열이 여기에 해당한다.
	 */
	public boolean isResourceNotFound() {
		return getHttpStatus() == 404;
	}
}
