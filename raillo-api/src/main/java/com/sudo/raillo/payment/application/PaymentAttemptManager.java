package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentAttemptStartResult;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * attempt 라이프사이클을 각각 독립된 트랜잭션에서 커밋한다.
 *
 * <p>호출자가 트랜잭션을 시작하더라도 이 커밋들은 독립적이어야 한다. 그렇지 않으면 Toss 호출 중
 * 장애가 났을 때 복구 대상 attempt 기록이 남지 않으므로 REQUIRES_NEW를 유지한다.
 *
 * <p>승인 단계 전체의 조회·검증은 {@link PaymentApprovalStarter}가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAttemptManager {

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentValidator paymentValidator;

	/**
	 * 승인 시작 트랜잭션(TX A). Toss 호출 전에 IN_PROGRESS attempt를 커밋하는 유일한 지점이다.
	 *
	 * <p>같은 payment에 대한 동시 요청과 이전 시도의 잔재를 배제하기 위해 Payment에 pessimistic lock을 걸고 attempt 저장을 하나의 짧은 트랜잭션에서 처리한다. Toss 호출은 이 트랜잭션 밖(호출자)에서 이뤄진다.
	 *
	 * <p><b>3층 재검증 구조 중 두 번째 층(TX A).</b> pre-check({@link PaymentApprovalStarter})가 트랜잭션 없이 넘어가는 사이 attempt가 등록되거나 Payment 상태가 바뀌었을 가능성을 `SELECT FOR UPDATE`로 잠금을 잡은 뒤 다시 검증한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentAttemptStartResult startApprovalInNewTransaction(Long paymentId, String attemptId, String paymentKey) {
		// 잠금은 이 짧은 트랜잭션 안에서만 유지되고 Toss 호출 전에 해제된다.
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));

		Optional<PaymentAttempt> existing = paymentAttemptRepository.findByAttemptId(attemptId);
		if (existing.isPresent()) {
			paymentValidator.validateApprovalAttempt(existing.get(), paymentId, paymentKey);
			return new PaymentAttemptStartResult(existing.get(), false);
		}

		paymentValidator.validateApprovable(payment);
		// 같은 attemptId 재요청은 위 findByAttemptId가 처리했다. 여기서는 다른 attemptId의 새 시도가 같은 Payment의 진행 중·확정된 attempt 위에 중복되지 않도록 최근 attempt 상태를 확인한다.
		paymentAttemptRepository.findLatestApprovalByPaymentId(paymentId).ifPresent(previous -> {
			switch (previous.getStatus()) {
				case IN_PROGRESS -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
				case SUCCEEDED -> throw new BusinessException(PaymentError.PAYMENT_ALREADY_COMPLETED);
				case FAILED -> { /* 재시도 허용 (다른 카드) */ }
			}
		});

		// Payment.paymentKey는 승인 확정 시에만 세팅한다. TX A에서는 PaymentAttempt에만 paymentKey를 저장한다.
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, attemptId, paymentKey);
		return new PaymentAttemptStartResult(paymentAttemptRepository.save(attempt), true);
	}

	/**
	 * 이미 종결(SUCCEEDED/FAILED)된 attempt에 대한 재호출은 no-op으로 종료한다. 동시 재시도 경합에서 뒤늦게 도착한 markFailed 호출을 무해하게 종결하기 위한 idempotency 방어.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailedInNewTransaction(Long attemptDbId, String errorCode, String errorMessage) {
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));

		if (attempt.getStatus() != PaymentAttemptStatus.IN_PROGRESS) {
			log.info("[markFailed - 이미 종결된 attempt, no-op] attemptId={}, currentStatus={}", attempt.getAttemptId(), attempt.getStatus());
			return;
		}
		attempt.markFailed(errorCode, errorMessage);
	}
}
