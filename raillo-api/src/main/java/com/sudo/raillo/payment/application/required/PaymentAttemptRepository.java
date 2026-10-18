package com.sudo.raillo.payment.application.required;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

public interface PaymentAttemptRepository {

	PaymentAttempt save(PaymentAttempt attempt);

	Optional<PaymentAttempt> findById(Long id);

	Optional<PaymentAttempt> findByAttemptId(String attemptId);

	Optional<PaymentAttempt> findLatestApprovalByPaymentId(Long paymentId);

	/** Recovery Worker가 집어갈 수 있는 후보를 선점한다. */
	List<PaymentAttempt> lockClaimable(PaymentAttemptType type, LocalDateTime staleBefore, LocalDateTime now, int limit);

	/** 운영 지표용: 상태별 attempt 수를 센다. */
	long countByStatus(PaymentAttemptStatus status);

	/** 운영 지표용: 주어진 시각 이후에 바뀐 상태별 attempt 수를 센다. */
	long countByStatusUpdatedAfter(PaymentAttemptStatus status, LocalDateTime updatedAfter);

	/** 운영 지표용: 오래된 진행 중 attempt 수를 센다. */
	long countStaleInProgress(PaymentAttemptType type, LocalDateTime staleBefore);
}
