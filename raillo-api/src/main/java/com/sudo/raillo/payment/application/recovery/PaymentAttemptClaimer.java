package com.sudo.raillo.payment.application.recovery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

import lombok.RequiredArgsConstructor;

/** 후보를 집어 소유자와 lease를 각인한다. 이 트랜잭션은 외부 호출을 포함하지 않는다. */
@Component
@RequiredArgsConstructor
public class PaymentAttemptClaimer {

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final RecoveryProperties properties;
	private final String owner = resolveOwner();

	@Transactional
	public List<Long> claim(LocalDateTime now) {
		List<PaymentAttempt> candidates = paymentAttemptRepository.lockClaimable(
			PaymentAttemptType.APPROVAL, now.minus(properties.staleAfter()), now, properties.batchSize());
		candidates.forEach(attempt -> attempt.startProcessing(owner, now.plus(properties.leaseDuration())));
		return candidates.stream().map(PaymentAttempt::getId).toList();
	}

	private static String resolveOwner() {
		String hostname = System.getenv("HOSTNAME");
		return hostname == null || hostname.isBlank() ? UUID.randomUUID().toString() : hostname;
	}
}
