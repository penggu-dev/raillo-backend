package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.application.required.OutboxMetrics;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>실패는 두 갈래다. 재시도로 결과가 달라질 수 있는 실패는 지수 백오프로 최대 재시도까지 다시 시도한다.
 * 에러 코드가 재시도 불가를 선언한 실패는 백오프를 태우지 않고 바로 FAILED로 보낸다. 몇 번 더 시도해도
 * 같은 응답이 올 것이 확정이라, 재시도는 사람이 알아차리는 시점만 늦춘다.</p>
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

	@Transactional
	public void poll() {
		LocalDateTime now = LocalDateTime.now();
		List<PaymentOutbox> batch = outboxRepository.lockProcessable(now, properties.batchSize(), dispatcher.supportedTypes());
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
			recordFailure(row, now, e);
		}
		outboxRepository.save(row);
	}

	/** 재시도 불가는 즉시 FAILED, 최대 재시도 초과도 FAILED, 그 외는 백오프 후 재시도. */
	private void recordFailure(PaymentOutbox row, LocalDateTime now, Exception e) {
		log.warn("[Outbox 처리 실패] id={}, retryCount={}, cause={}",
			row.getId(), row.getRetryCount(), e.toString());
		outboxMetrics.incrementCleanupFailure();

		if (!retryPolicy.isRetryable(e)) {
			giveUp(row, true);
			log.error("[Outbox 재시도 불가 - 즉시 FAILED] id={}, type={}, retryCount={}, cause={}",
				row.getId(), row.getType(), row.getRetryCount(), e.toString(), e);
			return;
		}

		int nextRetryCount = row.getRetryCount() + 1;
		if (retryPolicy.shouldGiveUp(nextRetryCount)) {
			giveUp(row, false);
			log.error("[Outbox 최대 재시도 초과] id={}, retryCount={}", row.getId(), nextRetryCount);
			return;
		}

		row.markRetry(retryPolicy.nextRetryAt(now, row.getRetryCount()));
	}

	/**
	 * FAILED 전이와 그에 딸린 카운터를 한곳에서 올린다.
	 *
	 * <p>FAILED로 보내는 경로가 둘이라 각자 카운터를 올리면 한쪽에만 새 카운터가 추가되는 드리프트가 생긴다.
	 *
	 * @param nonRetryable 에러 코드가 재시도 불가를 선언해 백오프를 태우지 않고 FAILED로 보낸 경우
	 */
	private void giveUp(PaymentOutbox row, boolean nonRetryable) {
		row.markFailed();
		outboxMetrics.incrementOutboxFailed();
		if (nonRetryable) {
			outboxMetrics.incrementOutboxNonRetryable();
		}
	}

	private void dispatchInNewTransaction(PaymentOutbox row) {
		dispatchTransactionTemplate.executeWithoutResult(
			status -> dispatcher.dispatch(row.getType(), row.getPayload())
		);
	}
}
