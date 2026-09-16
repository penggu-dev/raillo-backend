package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.application.required.OutboxMetrics;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PaymentOutbox의 처리 가능한 PENDING 행을 주기적으로 폴링해 dispatcher에 위임한다.
 *
 * <p>외부 트랜잭션(poll)은 SKIP LOCKED로 배치를 선점한다. dispatcher 호출은 별도 REQUIRES_NEW 트랜잭션에서 수행하므로,
 * 처리기 내부의 @Transactional 서비스에서 발생한 예외가 outer 트랜잭션을 rollback-only로 오염시키지 않는다.
 * 결과적으로 한 행의 처리 실패가 같은 배치의 다른 정상 행 커밋이나 실패 행의 재시도 상태 저장을 롤백시키지 않는다.
 */
@Slf4j
@Component
public class PaymentOutboxWorker {

	private final PaymentOutboxRepository outboxRepository;
	private final OutboxEventDispatcher dispatcher;
	private final OutboxRetryPolicy retryPolicy;
	private final OutboxProperties properties;
	private final OutboxMetrics outboxMetrics;
	private final TransactionTemplate dispatchTransactionTemplate;

	public PaymentOutboxWorker(
		PaymentOutboxRepository outboxRepository,
		OutboxEventDispatcher dispatcher,
		OutboxRetryPolicy retryPolicy,
		OutboxProperties properties,
		OutboxMetrics outboxMetrics,
		PlatformTransactionManager transactionManager
	) {
		this.outboxRepository = outboxRepository;
		this.dispatcher = dispatcher;
		this.retryPolicy = retryPolicy;
		this.properties = properties;
		this.outboxMetrics = outboxMetrics;
		this.dispatchTransactionTemplate = new TransactionTemplate(transactionManager);
		this.dispatchTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Scheduled(fixedDelayString = "${raillo.payment.outbox.polling-interval}")
	@Transactional
	public void poll() {
		LocalDateTime now = LocalDateTime.now();
		List<PaymentOutbox> batch = outboxRepository.lockProcessable(now, properties.batchSize());
		if (batch.isEmpty()) {
			return;
		}

		for (PaymentOutbox row : batch) {
			processSingle(row, now);
		}
	}

	private void processSingle(PaymentOutbox row, LocalDateTime now) {
		try {
			dispatchInNewTransaction(row);
			row.markDone();
		} catch (Exception e) {
			log.warn("[Outbox 처리 실패] id={}, retryCount={}, cause={}",
				row.getId(), row.getRetryCount(), e.toString());
			outboxMetrics.incrementCleanupFailure();
			int nextRetryCount = row.getRetryCount() + 1;
			if (retryPolicy.shouldGiveUp(nextRetryCount)) {
				row.markFailed();
				outboxMetrics.incrementOutboxFailed();
				log.error("[Outbox 최대 재시도 초과] id={}, retryCount={}", row.getId(), nextRetryCount);
			} else {
				row.markRetry(retryPolicy.nextRetryAt(now, row.getRetryCount()));
			}
		}
		outboxRepository.save(row);
	}

	private void dispatchInNewTransaction(PaymentOutbox row) {
		dispatchTransactionTemplate.executeWithoutResult(
			status -> dispatcher.dispatch(row.getType(), row.getPayload())
		);
	}
}
