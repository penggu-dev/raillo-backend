package com.sudo.raillo.payment.application.recovery;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.AttemptError;
import com.sudo.raillo.payment.application.AttemptFailureMarker;
import com.sudo.raillo.payment.application.PaymentApprovalReconciler;
import com.sudo.raillo.payment.application.PaymentAttemptManager;
import com.sudo.raillo.payment.application.PaymentConfirmTarget;
import com.sudo.raillo.payment.application.PaymentSeatGuard;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.application.result.ReconcileDecision;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오래된 진행 중 승인 시도를 게이트웨이와 대사한다. 트랜잭션을 열지 않는다. 선점은 {@link PaymentAttemptClaimer}가,
 * 상태 기록은 {@link AttemptFailureMarker}가 각자의 트랜잭션에서 커밋한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryWorker {

	private final PaymentAttemptClaimer claimer;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentApprovalReconciler reconciler;
	private final AttemptFailureMarker attemptFailureMarker;
	private final PaymentSeatGuard paymentSeatGuard;
	private final PaymentAttemptManager paymentAttemptManager;
	private final RecoveryBackoffPolicy backoffPolicy;

	public void poll() {
		LocalDateTime now = LocalDateTime.now();
		List<Long> claimed = claimer.claim(now);
		if (claimed.isEmpty()) {
			return;
		}
		log.info("[복구 Worker 선점] count={}", claimed.size());
		claimed.forEach(attemptDbId -> recoverOne(attemptDbId, now));
	}

	/** attempt 조회 실패는 점유만 반납하고, attempt를 읽은 뒤의 실패는 attempt의 createdAt으로 백오프한다. */
	private void recoverOne(Long attemptDbId, LocalDateTime now) {
		PaymentAttempt attempt;
		try {
			attempt = paymentAttemptRepository.findById(attemptDbId)
				.orElseThrow(() -> new IllegalStateException("attempt를 찾을 수 없음: attemptDbId=" + attemptDbId));
		} catch (RuntimeException e) {
			log.error("[복구 Worker 처리 실패 - attempt 조회] attemptDbId={}, cause={}", attemptDbId, e.toString(), e);
			paymentAttemptManager.releaseProcessingInNewTransaction(attemptDbId);
			return;
		}
		try {
			Payment payment = paymentRepository.findById(attempt.getPaymentId())
				.orElseThrow(() -> new IllegalStateException(
					"payment를 찾을 수 없음: attemptDbId=" + attemptDbId + ", paymentId=" + attempt.getPaymentId()));
			apply(reconciler.reconcile(PaymentConfirmTarget.of(attempt, payment)), attempt, payment, now);
		} catch (RuntimeException e) {
			log.error("[복구 Worker 처리 실패] attemptDbId={}, cause={}", attemptDbId, e.toString(), e);
			backOff(attempt, now);
		}
	}

	private void apply(ReconcileDecision decision, PaymentAttempt attempt, Payment payment, LocalDateTime now) {
		String orderCode = payment.getOrderCode();
		switch (decision) {
			case ReconcileDecision.Confirmable _ -> {
				log.info("[복구 Worker - 확정 재실행 대상] attemptId={}", attempt.getAttemptId());
				backOff(attempt, now);
			}
			case ReconcileDecision.DefinitiveFailure failure -> {
				attemptFailureMarker.markFailedQuietly(payment.getId(), attempt.getId(),
					new AttemptError(failure.errorCode(), failure.message()));
				paymentSeatGuard.releaseQuietly(orderCode, payment.getId(), attempt.getId());
				release(attempt);
			}
			case ReconcileDecision.ReviewRequired review -> {
				attemptFailureMarker.markReviewRequiredQuietly(payment.getId(), attempt.getId(),
					new AttemptError(review.errorCode(), review.message()));
				paymentSeatGuard.releaseQuietly(orderCode, payment.getId(), attempt.getId());
				release(attempt);
			}
			case ReconcileDecision.StillPending _, ReconcileDecision.QueryUncertain _ -> {
				paymentSeatGuard.holdQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt, now);
			}
			case ReconcileDecision.UnexpectedStatus unexpected -> {
				log.error("[복구 Worker - 쓰지 않는 게이트웨이 상태] status={}, attemptId={}",
					unexpected.status(), attempt.getAttemptId());
				paymentSeatGuard.holdQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt, now);
			}
		}
	}

	private void backOff(PaymentAttempt attempt, LocalDateTime now) {
		paymentAttemptManager.scheduleRetryInNewTransaction(
			attempt.getId(), backoffPolicy.nextRetryAt(now, attempt.getCreatedAt()));
	}

	private void release(PaymentAttempt attempt) {
		paymentAttemptManager.releaseProcessingInNewTransaction(attempt.getId());
	}
}
