package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PaymentOutbox의 처리 가능한 PENDING 행을 주기적으로 폴링해 dispatcher에 위임한다.
 *
 * <p>배치 전체를 하나의 트랜잭션으로 묶는다. 개별 행의 처리 실패는 예외를 삼켜서 다른 행의 상태 전이 커밋을 막지 않는다.
 * dispatcher가 발생시키는 부수 효과(Redis 정리 등)는 트랜잭션과 무관하게 즉시 반영됨을 유의.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxWorker {

	private final PaymentOutboxRepository outboxRepository;
	private final OutboxEventDispatcher dispatcher;
	private final OutboxRetryPolicy retryPolicy;
	private final OutboxProperties properties;

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
			dispatcher.dispatch(row.getType(), row.getPayload());
			row.markDone();
		} catch (Exception e) {
			log.warn("[Outbox 처리 실패] id={}, retryCount={}, cause={}",
				row.getId(), row.getRetryCount(), e.toString());
			int nextRetryCount = row.getRetryCount() + 1;
			if (retryPolicy.shouldGiveUp(nextRetryCount)) {
				row.markFailed();
				log.error("[Outbox 최대 재시도 초과] id={}, retryCount={}", row.getId(), nextRetryCount);
			} else {
				row.markRetry(retryPolicy.nextRetryAt(now, row.getRetryCount()));
			}
		}
		outboxRepository.save(row);
	}
}
