package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ServiceTest
class PaymentOutboxWorkerIntegrationTest {

	@Autowired
	private PaymentOutboxWorker worker;

	@Autowired
	private PaymentOutboxRepository outboxRepository;

	@Autowired
	private OutboxProperties properties;

	@MockitoBean
	private OutboxEventDispatcher dispatcher;

	@Test
	@DisplayName("PENDING 행을 처리기가 성공적으로 처리하면 DONE으로 전이한다")
	void poll_processesPending_andMarksDone() {
		// given
		PaymentOutbox saved = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(1L, "k-done", "{}")
		);
		doNothing().when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		PaymentOutbox reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
		assertThat(reloaded.getProcessedAt()).isNotNull();
		assertThat(reloaded.getRetryCount()).isZero();
	}

	@Test
	@DisplayName("일시 실패 시 retryCount가 1 증가하고 nextRetryAt이 미래로 설정된다")
	void poll_transientFailure_marksRetryWithBackoff() {
		// given
		PaymentOutbox saved = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(2L, "k-retry", "{}")
		);
		doThrow(new RuntimeException("transient"))
			.when(dispatcher).dispatch(eq(PaymentOutboxType.BOOKING_CONFIRMED), any());
		LocalDateTime beforePoll = LocalDateTime.now();

		// when
		worker.poll();

		// then
		PaymentOutbox reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(reloaded.getRetryCount()).isEqualTo(1);
		assertThat(reloaded.getNextRetryAt())
			.isAfterOrEqualTo(beforePoll)
			.isBefore(beforePoll.plus(properties.maxBackoff()).plusSeconds(1));
	}

	@Test
	@DisplayName("최대 재시도 초과 시 FAILED로 전이한다")
	void poll_exhaustedRetries_marksFailed() {
		// given: retryCount를 max - 1까지 올린 뒤 한 번 더 실패시킨다
		PaymentOutbox row = PaymentOutbox.forBookingConfirmed(3L, "k-fail", "{}");
		row = outboxRepository.save(row);
		for (int i = 0; i < properties.maxRetries() - 1; i++) {
			row.markRetry(LocalDateTime.now().minusSeconds(1));
			row = outboxRepository.save(row);
		}
		doThrow(new RuntimeException("still failing"))
			.when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		PaymentOutbox reloaded = outboxRepository.findById(row.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
		assertThat(reloaded.getProcessedAt()).isNotNull();
	}

	@Test
	@DisplayName("nextRetryAt이 미래인 PENDING은 처리하지 않는다")
	void poll_skipsRowsWithFutureNextRetryAt() {
		// given
		PaymentOutbox row = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(4L, "k-future", "{}")
		);
		row.markRetry(LocalDateTime.now().plusHours(1));
		outboxRepository.save(row);

		// when
		worker.poll();

		// then
		PaymentOutbox reloaded = outboxRepository.findById(row.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(reloaded.getRetryCount()).isEqualTo(1);
	}
}
