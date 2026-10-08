package com.sudo.raillo.payment.application.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentGatewayExceptionTest {

	private static PaymentGatewayException of(int httpStatus, DeliveryPhase phase) {
		return new PaymentGatewayException(httpStatus, "CODE", "message", "TOSS", "PAYMENT", phase) {
		};
	}

	@Test
	@DisplayName("NOT_REACHED는 미도달로만 판정된다")
	void notReached() {
		PaymentGatewayException e = of(0, DeliveryPhase.NOT_REACHED);

		assertThat(e.isNotReached()).isTrue();
		assertThat(e.isOutcomeUnknown()).isFalse();
		assertThat(e.isDefinitiveFailure()).isFalse();
	}

	@Test
	@DisplayName("NO_RESPONSE는 결과 불명으로 판정된다")
	void noResponse() {
		PaymentGatewayException e = of(0, DeliveryPhase.NO_RESPONSE);

		assertThat(e.isNotReached()).isFalse();
		assertThat(e.isOutcomeUnknown()).isTrue();
		assertThat(e.isDefinitiveFailure()).isFalse();
	}

	@Test
	@DisplayName("응답 4xx는 확정 실패, 5xx는 결과 불명이다")
	void answered() {
		assertThat(of(400, DeliveryPhase.ANSWERED).isDefinitiveFailure()).isTrue();
		assertThat(of(400, DeliveryPhase.ANSWERED).isOutcomeUnknown()).isFalse();
		assertThat(of(500, DeliveryPhase.ANSWERED).isOutcomeUnknown()).isTrue();
		assertThat(of(500, DeliveryPhase.ANSWERED).isDefinitiveFailure()).isFalse();
	}
}
