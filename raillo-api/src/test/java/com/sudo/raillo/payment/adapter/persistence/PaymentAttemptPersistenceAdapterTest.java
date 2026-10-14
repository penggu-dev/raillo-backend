package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class PaymentAttemptPersistenceAdapterTest {

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("저장한 attempt를 attemptId로 조회한다")
	void save_and_findByAttemptId() {
		PaymentAttempt saved = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key")
		);

		var found = paymentAttemptRepository.findByAttemptId("attempt-abc");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
		assertThat(found.get().getStatus()).isEqualTo(saved.getStatus());
	}

	@Test
	@DisplayName("오래되고 점유되지 않은 진행 중 시도만 선점 후보로 돌려준다")
	void lockClaimable_returnsOnlyUnclaimedStaleRows() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt claimable = paymentAttemptRepository.save(PaymentAttempt.startApproval(1L, "a-claimable", "k1"));
		PaymentAttempt leased = paymentAttemptRepository.save(PaymentAttempt.startApproval(2L, "a-leased", "k2"));
		leased.startProcessing("pod-other", now.plusMinutes(5));
		paymentAttemptRepository.save(leased);
		PaymentAttempt backoff = paymentAttemptRepository.save(PaymentAttempt.startApproval(3L, "a-backoff", "k3"));
		backoff.scheduleRetry(now.plusMinutes(10));
		paymentAttemptRepository.save(backoff);

		// when
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		List<PaymentAttempt> claimed = transactionTemplate.execute(status ->
			paymentAttemptRepository.lockClaimable(PaymentAttemptType.APPROVAL, now.plusDays(1), now, 10));

		// then
		assertThat(claimed).extracting(PaymentAttempt::getAttemptId).containsExactly("a-claimable");
	}

	@Test
	@DisplayName("lease가 지난 시도는 다시 선점 후보가 된다")
	void lockClaimable_reclaimsExpiredLease() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt abandoned = paymentAttemptRepository.save(PaymentAttempt.startApproval(1L, "a-abandoned", "k1"));
		abandoned.startProcessing("pod-dead", now.minusMinutes(1));
		paymentAttemptRepository.save(abandoned);

		// when
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		List<PaymentAttempt> claimed = transactionTemplate.execute(status ->
			paymentAttemptRepository.lockClaimable(PaymentAttemptType.APPROVAL, now.plusDays(1), now, 10));

		// then
		assertThat(claimed).extracting(PaymentAttempt::getAttemptId).containsExactly("a-abandoned");
	}

	@Test
	@DisplayName("수동 확인 대기 행 수와 오래된 진행 중 행 수를 센다")
	void countsExposeOperationalGauges() {
		// given
		LocalDateTime now = LocalDateTime.now();
		PaymentAttempt stale = paymentAttemptRepository.save(PaymentAttempt.startApproval(1L, "a-stale", "k1"));
		PaymentAttempt reviewed = paymentAttemptRepository.save(PaymentAttempt.startApproval(2L, "a-review", "k2"));
		reviewed.markReviewRequired("REVIEW_RESULT_MISMATCH", "불일치");
		paymentAttemptRepository.save(reviewed);

		// when & then
		assertThat(paymentAttemptRepository.countByStatus(PaymentAttemptStatus.REVIEW_REQUIRED)).isEqualTo(1);
		assertThat(paymentAttemptRepository.countStaleInProgress(PaymentAttemptType.APPROVAL, now.plusDays(1)))
			.isEqualTo(1);
		assertThat(stale.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
	}
}
