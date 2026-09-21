package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@ServiceTest
@Import(PaymentOutboxWorkerIntegrationTest.FailingTxServiceConfig.class)
class PaymentOutboxWorkerIntegrationTest {

	@Autowired
	private PaymentOutboxWorker worker;

	@Autowired
	private PaymentOutboxRepository outboxRepository;

	@Autowired
	private OutboxProperties properties;

	@Autowired
	private MeterRegistry meterRegistry;

	@MockitoBean
	private OutboxEventDispatcher dispatcher;

	@Autowired
	private FailingTxService failingTxService;

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
	@DisplayName("일시 실패 시 payment.cleanup.failure 카운터가 증가한다")
	void poll_transientFailure_incrementsCleanupFailureCounter() {
		double before = meterRegistry.counter("payment.cleanup.failure").count();
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(5L, "k-metric-retry", "{}"));
		doThrow(new RuntimeException("boom"))
			.when(dispatcher).dispatch(any(), any());

		worker.poll();

		assertThat(meterRegistry.counter("payment.cleanup.failure").count()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("최대 재시도 초과 시 payment.outbox.failed 카운터가 증가한다")
	void poll_exhaustedRetries_incrementsOutboxFailedCounter() {
		double before = meterRegistry.counter("payment.outbox.failed").count();
		PaymentOutbox row = outboxRepository.save(PaymentOutbox.forBookingConfirmed(6L, "k-metric-fail", "{}"));
		for (int i = 0; i < properties.maxRetries() - 1; i++) {
			row.markRetry(LocalDateTime.now().minusSeconds(1));
			row = outboxRepository.save(row);
		}
		doThrow(new RuntimeException("boom"))
			.when(dispatcher).dispatch(any(), any());

		worker.poll();

		assertThat(meterRegistry.counter("payment.outbox.failed").count()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("처리기가 @Transactional 서비스에서 예외를 던져도 배치의 다른 정상 행 커밋과 실패 행의 재시도 상태가 롤백되지 않는다")
	void poll_innerTransactionalFailure_doesNotRollbackBatch() {
		// given: 두 건 PENDING — payload의 마커로 실패/성공을 구분
		PaymentOutbox failingRow = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(10L, "k-tx-fail", "{\"marker\":\"fail\"}")
		);
		PaymentOutbox successRow = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(11L, "k-tx-success", "{\"marker\":\"ok\"}")
		);

		// dispatcher가 실제 @Transactional 서비스 예외를 던지도록 스텁 — outer tx 오염 시나리오 재현
		doAnswer(invocation -> {
			String payload = invocation.getArgument(1);
			if (payload.contains("\"fail\"")) {
				failingTxService.throwInsideTransaction();
			}
			return null;
		}).when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then: 실패 행은 재시도 상태로 저장, 정상 행은 DONE으로 저장 — 배치 전체 커밋이 유지됨
		PaymentOutbox reloadedFailing = outboxRepository.findById(failingRow.getId()).orElseThrow();
		assertThat(reloadedFailing.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(reloadedFailing.getRetryCount()).isEqualTo(1);
		assertThat(reloadedFailing.getNextRetryAt()).isNotNull();

		PaymentOutbox reloadedSuccess = outboxRepository.findById(successRow.getId()).orElseThrow();
		assertThat(reloadedSuccess.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
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

	@Service
	static class FailingTxService {
		@Transactional
		public void throwInsideTransaction() {
			throw new RuntimeException("simulated tx-tainting failure");
		}
	}

	@TestConfiguration
	static class FailingTxServiceConfig {
		@org.springframework.context.annotation.Bean
		FailingTxService failingTxService() {
			return new FailingTxService();
		}
	}
}
