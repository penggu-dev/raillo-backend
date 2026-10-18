package com.sudo.raillo.payment.application.recovery;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.AttemptError;
import com.sudo.raillo.payment.application.AttemptFailureMarker;
import com.sudo.raillo.payment.application.PaymentApprovalFinalizer;
import com.sudo.raillo.payment.application.PaymentApprovalReconciler;
import com.sudo.raillo.payment.application.PaymentAttemptManager;
import com.sudo.raillo.payment.application.PaymentConfirmTarget;
import com.sudo.raillo.payment.application.PaymentSeatGuard;
import com.sudo.raillo.payment.application.exception.AttemptReviewRequiredException;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.application.required.RecoveryMetrics;
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
	@Qualifier("workerApprovalReconciler")
	private final PaymentApprovalReconciler reconciler;
	private final PaymentApprovalFinalizer paymentApprovalFinalizer;
	private final AttemptFailureMarker attemptFailureMarker;
	private final PaymentSeatGuard paymentSeatGuard;
	private final PaymentAttemptManager paymentAttemptManager;
	private final RecoveryBackoffPolicy backoffPolicy;
	private final RecoveryMetrics recoveryMetrics;

	public void poll() {
		List<Long> claimed = claimer.claim(LocalDateTime.now());
		if (claimed.isEmpty()) {
			return;
		}
		log.info("[복구 Worker 선점] count={}", claimed.size());
		claimed.forEach(this::recoverOne);
	}

	/** attempt 조회 실패는 점유만 반납하고, attempt를 읽은 뒤의 실패는 attempt의 createdAt으로 백오프한다. */
	private void recoverOne(Long attemptDbId) {
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
			if (backoffPolicy.isMaxWaitExceeded(attempt.getCreatedAt(), LocalDateTime.now())) {
				giveUp(attempt, payment);
				return;
			}
			apply(reconciler.reconcile(PaymentConfirmTarget.of(attempt, payment)), attempt, payment);
		} catch (RuntimeException e) {
			log.error("[복구 Worker 처리 실패] attemptDbId={}, cause={}", attemptDbId, e.toString(), e);
			backOff(attempt);
		}
	}

	private void apply(ReconcileDecision decision, PaymentAttempt attempt, Payment payment) {
		recoveryMetrics.incrementDecision(decision.getClass().getSimpleName());
		String orderCode = payment.getOrderCode();
		switch (decision) {
			case ReconcileDecision.Confirmable confirmable -> finalizeRecovered(confirmable, attempt, payment);
			case ReconcileDecision.DefinitiveFailure failure -> {
				attemptFailureMarker.markFailedQuietly(payment.getId(), attempt.getId(),
					new AttemptError(failure.errorCode(), failure.message()));
				paymentSeatGuard.releaseQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt);
			}
			case ReconcileDecision.ReviewRequired review -> {
				attemptFailureMarker.markReviewRequiredQuietly(payment.getId(), attempt.getId(),
					new AttemptError(review.errorCode(), review.message()));
				paymentSeatGuard.releaseQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt);
			}
			case ReconcileDecision.StillPending _, ReconcileDecision.QueryUncertain _ -> {
				paymentSeatGuard.holdQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt);
			}
			case ReconcileDecision.UnexpectedStatus unexpected -> {
				log.error("[복구 Worker - 쓰지 않는 게이트웨이 상태] status={}, attemptId={}",
					unexpected.status(), attempt.getAttemptId());
				paymentSeatGuard.holdQuietly(orderCode, payment.getId(), attempt.getId());
				backOff(attempt);
			}
		}
	}

	/** 게이트웨이가 승인 완료로 응답한 건의 로컬 확정을 재실행한다. Toss 승인은 다시 보내지 않는다. */
	private void finalizeRecovered(
		ReconcileDecision.Confirmable confirmable, PaymentAttempt attempt, Payment payment
	) {
		try {
			paymentApprovalFinalizer.finalizeApproval(payment.getId(), attempt.getId(), confirmable.gatewayResult());
			recoveryMetrics.incrementRecovered();
			release(attempt);
			log.info("[복구 Worker - 확정 재실행 완료] attemptId={}, paymentId={}", attempt.getAttemptId(), payment.getId());
		} catch (AttemptReviewRequiredException review) {
			attemptFailureMarker.markReviewRequiredQuietly(payment.getId(), attempt.getId(), review.getAttemptError());
			paymentSeatGuard.releaseQuietly(payment.getOrderCode(), payment.getId(), attempt.getId());
			release(attempt);
		} catch (BusinessException rejected) {
			log.warn("[복구 Worker - 확정 거절] attemptId={}, errorCode={}", attempt.getAttemptId(), rejected.getErrorCode());
			backOff(attempt);
		}
	}

	/** 최대 대기를 넘긴 시도를 수동 확인으로 종결한다. */
	private void giveUp(PaymentAttempt attempt, Payment payment) {
		log.error("[복구 Worker - 최대 대기 초과] attemptId={}, paymentId={}, createdAt={}",
			attempt.getAttemptId(), payment.getId(), attempt.getCreatedAt());
		attemptFailureMarker.markReviewRequiredQuietly(payment.getId(), attempt.getId(),
			new AttemptError("REVIEW_RECOVERY_TIMEOUT", "복구 최대 대기 시간을 초과해 사람이 확인해야 합니다."));
		paymentSeatGuard.releaseQuietly(payment.getOrderCode(), payment.getId(), attempt.getId());
		release(attempt);
	}

	/** 기준 시각을 이 시점에 다시 읽는다. 종결 분기에서도 써서 마킹이 조용히 실패한 행이 바로 다시 집히지 않게 한다. */
	private void backOff(PaymentAttempt attempt) {
		paymentAttemptManager.scheduleRetryInNewTransaction(
			attempt.getId(), backoffPolicy.nextRetryAt(LocalDateTime.now(), attempt.getCreatedAt()));
	}

	private void release(PaymentAttempt attempt) {
		paymentAttemptManager.releaseProcessingInNewTransaction(attempt.getId());
	}
}
