package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class PaymentOutboxPersistenceAdapterTest {

	@Autowired
	private PaymentOutboxRepository paymentOutboxRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("저장한 outbox를 deduplication key로 조회한다")
	void save_and_findByDedup() {
		PaymentOutbox saved = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(100L, "payment:100:booking-confirmed", "{}")
		);

		var found = paymentOutboxRepository.findByDeduplicationKey("payment:100:booking-confirmed");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
	}

	@Test
	@DisplayName("next_retry_at이 null 또는 now 이하인 PENDING만 반환한다")
	void findProcessable_filters() {
		PaymentOutbox immediate = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(1L, "k-1", "{}")
		);
		PaymentOutbox later = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(2L, "k-2", "{}")
		);
		later.markRetry(LocalDateTime.now().plusHours(1));
		paymentOutboxRepository.save(later);

		List<PaymentOutbox> results = paymentOutboxRepository.findProcessable(LocalDateTime.now(), 10);

		assertThat(results).extracting(PaymentOutbox::getId).containsExactly(immediate.getId());
	}

	@Test
	@DisplayName("두 트랜잭션이 동시에 lockProcessable을 호출하면 SKIP LOCKED로 서로 다른 행을 잡는다")
	void lockProcessable_skipsRowsHeldByOtherTransaction() throws Exception {
		// given: PENDING 3건 저장
		PaymentOutbox row1 = paymentOutboxRepository.save(PaymentOutbox.forBookingConfirmed(1L, "k-1", "{}"));
		PaymentOutbox row2 = paymentOutboxRepository.save(PaymentOutbox.forBookingConfirmed(2L, "k-2", "{}"));
		PaymentOutbox row3 = paymentOutboxRepository.save(PaymentOutbox.forBookingConfirmed(3L, "k-3", "{}"));

		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		CountDownLatch aLocked = new CountDownLatch(1);
		CountDownLatch bDone = new CountDownLatch(1);
		AtomicReference<List<Long>> threadAIds = new AtomicReference<>();
		AtomicReference<List<Long>> threadBIds = new AtomicReference<>();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// when: A가 트랜잭션 안에서 2건 선점 후 대기, 그 사이 B가 lockProcessable 호출
			executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				List<PaymentOutbox> locked = paymentOutboxRepository.lockProcessable(LocalDateTime.now(), 2);
				threadAIds.set(locked.stream().map(PaymentOutbox::getId).toList());
				aLocked.countDown();
				try {
					bDone.await(3, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));

			assertThat(aLocked.await(3, TimeUnit.SECONDS)).isTrue();

			executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				List<PaymentOutbox> locked = paymentOutboxRepository.lockProcessable(LocalDateTime.now(), 5);
				threadBIds.set(locked.stream().map(PaymentOutbox::getId).toList());
				bDone.countDown();
			}));

			assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdown();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}

		// then: 두 결과가 겹치지 않고, 총합이 3건이다
		assertThat(threadAIds.get()).hasSize(2);
		assertThat(threadBIds.get()).hasSize(1);
		assertThat(threadAIds.get()).doesNotContainAnyElementsOf(threadBIds.get());
		assertThat(List.of(row1.getId(), row2.getId(), row3.getId()))
			.containsExactlyInAnyOrderElementsOf(
				java.util.stream.Stream.concat(threadAIds.get().stream(), threadBIds.get().stream()).toList()
			);
	}
}
