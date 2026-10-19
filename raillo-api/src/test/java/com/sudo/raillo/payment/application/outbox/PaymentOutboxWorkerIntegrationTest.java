package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

	@org.junit.jupiter.api.BeforeEach
	void register_processor_types() {
		when(dispatcher.supportedTypes()).thenReturn(List.of(PaymentOutboxType.BOOKING_CONFIRMED));
	}

	@Test
	@DisplayName("PENDING 행을 처리기가 성공적으로 처리하면 DONE으로 전이한다")
	void poll_processesPending_andMarksDone() {
		// given
		PaymentOutbox saved = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(1L, "{}")
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
			PaymentOutbox.forBookingConfirmed(2L, "{}")
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
		PaymentOutbox row = PaymentOutbox.forBookingConfirmed(3L, "{}");
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
	@DisplayName("재시도 불가 에러는 재시도 횟수가 남아 있어도 백오프 없이 바로 FAILED로 전이한다")
	void poll_nonRetryableFailure_marksFailedImmediately() {
		// given - retryCount 0, 즉 재시도 여유가 maxRetries만큼 남은 상태
		PaymentOutbox saved = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(10L, "{}")
		);
		doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED))
			.when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		PaymentOutbox reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
		assertThat(reloaded.getProcessedAt()).isNotNull();
		assertThat(reloaded.getRetryCount()).isZero();
		assertThat(reloaded.getNextRetryAt()).isNull();
	}

	@Test
	@DisplayName("재시도 불가 에러는 payment.outbox.non_retryable 카운터를 증가시킨다")
	void poll_nonRetryableFailure_incrementsNonRetryableCounter() {
		double beforeNonRetryable = meterRegistry.counter("payment.outbox.non_retryable").count();
		double beforeFailed = meterRegistry.counter("payment.outbox.failed").count();
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(11L, "{}"));
		doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED))
			.when(dispatcher).dispatch(any(), any());

		worker.poll();

		assertThat(meterRegistry.counter("payment.outbox.non_retryable").count())
			.isEqualTo(beforeNonRetryable + 1);
		assertThat(meterRegistry.counter("payment.outbox.failed").count())
			.isEqualTo(beforeFailed + 1);
	}

	@Test
	@DisplayName("FAILED로 종착한 행은 payment.outbox.failed_rows 게이지에 반영된다")
	void failedRow_isVisibleInGauge() {
		// 게이지가 등록돼 있지 않으면 get()이 던진다 — 등록 여부도 함께 검증한다
		double before = meterRegistry.get("payment.outbox.failed_rows").gauge().value();
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(12L, "{}"));
		doThrow(new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED))
			.when(dispatcher).dispatch(any(), any());

		worker.poll();

		assertThat(meterRegistry.get("payment.outbox.failed_rows").gauge().value()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("일시 실패 시 payment.cleanup.failure 카운터가 증가한다")
	void poll_transientFailure_incrementsCleanupFailureCounter() {
		double before = meterRegistry.counter("payment.cleanup.failure").count();
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(5L, "{}"));
		doThrow(new RuntimeException("boom"))
			.when(dispatcher).dispatch(any(), any());

		worker.poll();

		assertThat(meterRegistry.counter("payment.cleanup.failure").count()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("최대 재시도 초과 시 payment.outbox.failed 카운터가 증가한다")
	void poll_exhaustedRetries_incrementsOutboxFailedCounter() {
		double before = meterRegistry.counter("payment.outbox.failed").count();
		PaymentOutbox row = outboxRepository.save(PaymentOutbox.forBookingConfirmed(6L, "{}"));
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
			PaymentOutbox.forBookingConfirmed(10L, "{\"marker\":\"fail\"}")
		);
		PaymentOutbox successRow = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(11L, "{\"marker\":\"ok\"}")
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
			PaymentOutbox.forBookingConfirmed(4L, "{}")
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

	@Test
	@DisplayName("처리 가능한 행이 배치 크기보다 많으면 한 번의 poll에서 배치 크기만큼만 처리한다")
	void poll_processesAtMostBatchSize() {
		// given
		int overflow = properties.batchSize() + 2;
		for (int i = 0; i < overflow; i++) {
			outboxRepository.save(PaymentOutbox.forBookingConfirmed(100L + i, "{}"));
		}
		doNothing().when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		assertThat(outboxRepository.countByStatus(PaymentOutboxStatus.DONE)).isEqualTo(properties.batchSize());
		assertThat(outboxRepository.countByStatus(PaymentOutboxStatus.PENDING)).isEqualTo(overflow - properties.batchSize());
	}

	@Test
	@DisplayName("다음 조회 시각이 없는 행을 먼저, 그 다음은 조회 시각이 이른 순으로 처리한다")
	void poll_processesInNextRetryAtThenIdOrder() {
		// given 저장 순서와 기대 처리 순서를 다르게 둔다
		saveWithNextRetryAt(201L, LocalDateTime.now().minusSeconds(10));
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(202L, payloadOf(202L)));
		saveWithNextRetryAt(203L, LocalDateTime.now().minusMinutes(5));
		List<Long> dispatched = recordDispatchOrder();

		// when
		worker.poll();

		// then 조회 시각이 없는 202가 먼저, 그 다음 더 이른 203, 마지막이 201이다
		assertThat(dispatched).containsExactly(202L, 203L, 201L);
	}

	@Test
	@DisplayName("FAILED로 종착한 행은 다시 처리하지 않는다")
	void poll_skipsFailedRows() {
		// given
		PaymentOutbox row = outboxRepository.save(PaymentOutbox.forBookingConfirmed(301L, "{}"));
		row.markFailed();
		outboxRepository.save(row);
		doNothing().when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentOutboxStatus.FAILED);
		verify(dispatcher, never()).dispatch(any(), any());
	}

	@Test
	@DisplayName("이미 DONE인 행은 다시 처리하지 않는다")
	void poll_skipsDoneRows() {
		// given
		PaymentOutbox row = outboxRepository.save(PaymentOutbox.forBookingConfirmed(302L, "{}"));
		row.markDone();
		outboxRepository.save(row);
		doNothing().when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		verify(dispatcher, never()).dispatch(any(), any());
	}

	@Test
	@DisplayName("등록된 처리기가 없는 타입의 행은 선점하지 않는다")
	void poll_skipsUnsupportedTypes() {
		// given dispatcher가 지원하는 타입은 BOOKING_CONFIRMED뿐이다
		PaymentOutbox unsupported = outboxRepository.save(
			PaymentOutbox.forBookingSeatRelease(401L, "{}"));
		doNothing().when(dispatcher).dispatch(any(), any());

		// when
		worker.poll();

		// then
		assertThat(outboxRepository.findById(unsupported.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentOutboxStatus.PENDING);
		verify(dispatcher, never()).dispatch(any(), any());
	}

	@Test
	@DisplayName("앞선 Worker가 배치를 선점한 채 멈춰 있어도 다른 Worker는 막히지 않고 같은 행을 두 번 처리하지 않는다")
	void poll_concurrentWorkers_neverProcessSameRowTwice() throws Exception {
		// given 배치 크기의 두 배를 쌓는다. 선점한 쪽이 커밋 전에 멈춰 경합을 결정적으로 만든다
		int rows = properties.batchSize() * 2;
		for (int i = 0; i < rows; i++) {
			outboxRepository.save(PaymentOutbox.forBookingConfirmed(500L + i, payloadOf(500L + i)));
		}
		List<Long> dispatched = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch firstHoldsBatch = new CountDownLatch(1);
		CountDownLatch secondFinished = new CountDownLatch(1);
		doAnswer(invocation -> {
			dispatched.add(aggregateIdOf(invocation.getArgument(1)));
			if (firstHoldsBatch.getCount() > 0) {
				firstHoldsBatch.countDown();
				// SKIP LOCKED가 없으면 뒤 Worker가 이 잠금에 막혀 서로를 기다리다 타임아웃된다
				secondFinished.await(10, TimeUnit.SECONDS);
			}
			return null;
		}).when(dispatcher).dispatch(any(), any());

		// when
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> worker.poll());
			Future<?> second = pool.submit(() -> {
				awaitLatch(firstHoldsBatch);
				worker.poll();
				secondFinished.countDown();
			});
			second.get(30, TimeUnit.SECONDS);
			first.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		// then 선점한 배치가 겹치지 않으므로 같은 행이 두 번 위임되지 않는다
		assertThat(dispatched).doesNotHaveDuplicates();
		assertThat(outboxRepository.countByStatus(PaymentOutboxStatus.DONE)).isEqualTo(dispatched.size());
	}

	@Test
	@DisplayName("PENDING 행은 payment.outbox.pending 게이지에 반영된다")
	void pendingRows_areVisibleInGauge() {
		// given 게이지가 등록돼 있지 않으면 get()이 던진다
		double before = meterRegistry.get("payment.outbox.pending").gauge().value();

		// when
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(601L, "{}"));
		outboxRepository.save(PaymentOutbox.forBookingConfirmed(602L, "{}"));

		// then
		assertThat(meterRegistry.get("payment.outbox.pending").gauge().value()).isEqualTo(before + 2);
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("앞선 Worker가 배치를 선점하지 못했다");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private PaymentOutbox saveWithNextRetryAt(long aggregateId, LocalDateTime nextRetryAt) {
		PaymentOutbox row = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(aggregateId, payloadOf(aggregateId)));
		row.markRetry(nextRetryAt);
		return outboxRepository.save(row);
	}

	private static String payloadOf(long aggregateId) {
		return "{\"aggregateId\":" + aggregateId + "}";
	}

	/** payload에 심은 aggregateId로 dispatcher 호출 순서를 모은다. */
	private List<Long> recordDispatchOrder() {
		List<Long> order = new ArrayList<>();
		doAnswer(invocation -> {
			order.add(aggregateIdOf(invocation.getArgument(1)));
			return null;
		}).when(dispatcher).dispatch(any(), any());
		return order;
	}

	private static long aggregateIdOf(String payload) {
		return Long.parseLong(payload.replaceAll("[^0-9]", ""));
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
