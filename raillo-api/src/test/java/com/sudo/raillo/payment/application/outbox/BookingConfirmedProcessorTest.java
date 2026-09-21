package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.sudo.raillo.payment.domain.PaymentOutboxType;

class BookingConfirmedProcessorTest {
	@Test
	@DisplayName("미구현 예매 확정 처리기는 성공으로 반환하지 않는다")
	void unimplemented_confirmation_never_returns_success() {
		// given
		var processor = new BookingConfirmedProcessor();
		// when & then
		assertThat(processor.supports(PaymentOutboxType.BOOKING_CONFIRMED)).isTrue();
		assertThatThrownBy(() -> processor.process("{}"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
