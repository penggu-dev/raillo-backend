package com.sudo.raillo.payment.adapter.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentAttemptPersistenceAdapter implements PaymentAttemptRepository {

	private final PaymentAttemptJpaRepository jpaRepository;

	@Override
	public PaymentAttempt save(PaymentAttempt attempt) {
		return jpaRepository.save(attempt);
	}

	@Override
	public Optional<PaymentAttempt> findById(Long id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Optional<PaymentAttempt> findByAttemptId(String attemptId) {
		return jpaRepository.findByAttemptId(attemptId);
	}

	@Override
	public Optional<PaymentAttempt> findLatestApprovalByPaymentId(Long paymentId) {
		return jpaRepository.findFirstByPaymentIdAndAttemptTypeOrderByIdDesc(paymentId, PaymentAttemptType.APPROVAL);
	}

	@Override
	public List<PaymentAttempt> lockClaimable(PaymentAttemptType type, LocalDateTime staleBefore, LocalDateTime now, int limit) {
		return jpaRepository.lockClaimable(PaymentAttemptStatus.IN_PROGRESS, type, staleBefore, now, Limit.of(limit));
	}

	@Override
	public long countByStatus(PaymentAttemptStatus status) {
		return jpaRepository.countByStatus(status);
	}

	@Override
	public long countByStatusUpdatedAfter(PaymentAttemptStatus status, LocalDateTime updatedAfter) {
		return jpaRepository.countByStatusAndUpdatedAtAfter(status, updatedAfter);
	}

	@Override
	public long countStaleInProgress(PaymentAttemptType type, LocalDateTime staleBefore) {
		return jpaRepository.countStaleInProgress(PaymentAttemptStatus.IN_PROGRESS, type, staleBefore);
	}
}
