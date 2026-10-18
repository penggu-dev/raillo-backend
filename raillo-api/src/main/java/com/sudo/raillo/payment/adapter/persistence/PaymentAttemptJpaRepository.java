package com.sudo.raillo.payment.adapter.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

@Repository
public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

	Optional<PaymentAttempt> findByAttemptId(String attemptId);

	Optional<PaymentAttempt> findFirstByPaymentIdAndAttemptTypeOrderByIdDesc(Long paymentId, PaymentAttemptType type);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("""
		select a
		  from PaymentAttempt a
		 where a.status = :status
		   and a.attemptType = :type
		   and a.updatedAt < :staleBefore
		   and (a.nextRetryAt is null or a.nextRetryAt <= :now)
		   and (a.processingLeaseUntil is null or a.processingLeaseUntil < :now)
		 order by a.updatedAt asc
		""")
	List<PaymentAttempt> lockClaimable(
		@Param("status") PaymentAttemptStatus status,
		@Param("type") PaymentAttemptType type,
		@Param("staleBefore") LocalDateTime staleBefore,
		@Param("now") LocalDateTime now,
		Limit limit
	);

	long countByStatus(PaymentAttemptStatus status);

	long countByStatusAndUpdatedAtAfter(PaymentAttemptStatus status, LocalDateTime updatedAfter);

	@Query("""
		select count(a)
		  from PaymentAttempt a
		 where a.status = :status
		   and a.attemptType = :type
		   and a.updatedAt < :staleBefore
		""")
	long countStaleInProgress(
		@Param("status") PaymentAttemptStatus status,
		@Param("type") PaymentAttemptType type,
		@Param("staleBefore") LocalDateTime staleBefore
	);
}
