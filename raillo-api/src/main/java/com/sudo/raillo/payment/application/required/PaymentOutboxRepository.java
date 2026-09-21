package com.sudo.raillo.payment.application.required;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.sudo.raillo.payment.domain.PaymentOutbox;

public interface PaymentOutboxRepository {

	PaymentOutbox save(PaymentOutbox outbox);

	Optional<PaymentOutbox> findById(Long id);

	Optional<PaymentOutbox> findByDeduplicationKey(String deduplicationKey);

	/**
	 * 처리 가능한 PENDING 항목을 우선순위 순(next_retry_at 오름차순, null 우선)으로 반환한다.
	 */
	List<PaymentOutbox> findProcessable(LocalDateTime now, int limit);

	/**
	 * PESSIMISTIC_WRITE + SKIP LOCKED로 처리 가능한 PENDING 항목을 선점 조회한다.
	 * 반드시 트랜잭션 내에서 호출해야 하며, 트랜잭션이 커밋될 때까지 잠금이 유지된다.
	 */
	List<PaymentOutbox> lockProcessable(LocalDateTime now, int limit);
}
