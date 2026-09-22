package com.sudo.raillo.payment.adapter.webapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.payment.adapter.webapi.dto.PaymentConfirmRequest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class PaymentConfirmRequestTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	@DisplayName("필수 필드가 채워지면 검증을 통과하고 attemptId는 서버가 paymentKey에서 파생한다")
	void accepts_valid_request_and_derives_attempt_id() {
		PaymentConfirmRequest request = new PaymentConfirmRequest(
			"payment-key", "order-code", BigDecimal.valueOf(10000));

		assertThat(FACTORY.getValidator().validate(request)).isEmpty();
		assertThat(request.toCommand().attemptId()).hasSize(64);
	}
}
