package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.payment.domain.PaymentOutboxType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventDispatcherTest {

	@Test
	@DisplayName("타입을 지원하는 처리기가 있으면 그 처리기를 호출한다")
	void dispatch_routesToMatchingProcessor() {
		// given
		RecordingProcessor confirmed = new RecordingProcessor(PaymentOutboxType.BOOKING_CONFIRMED);
		OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(confirmed));

		// when
		dispatcher.dispatch(PaymentOutboxType.BOOKING_CONFIRMED, "payload-json");

		// then
		assertThat(confirmed.received).isEqualTo("payload-json");
	}

	@Test
	@DisplayName("지원 처리기가 없으면 예외를 던진다")
	void dispatch_throwsWhenNoProcessor() {
		// given
		OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of());

		// when·then
		assertThatThrownBy(() -> dispatcher.dispatch(PaymentOutboxType.BOOKING_CONFIRMED, "x"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BOOKING_CONFIRMED");
	}

	private static class RecordingProcessor implements OutboxEventProcessor {

		private final PaymentOutboxType supportedType;
		private String received;

		RecordingProcessor(PaymentOutboxType supportedType) {
			this.supportedType = supportedType;
		}

		@Override
		public boolean supports(PaymentOutboxType type) {
			return type == supportedType;
		}

		@Override
		public void process(String payload) {
			this.received = payload;
		}
	}
}
