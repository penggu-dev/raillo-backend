package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentAttemptStartResult;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.global.exception.ErrorCode;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentAttemptManagerTest {

	@Autowired private PaymentAttemptManager paymentAttemptManager;
	@Autowired private PaymentAttemptRepository paymentAttemptRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OrderRepository orderRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;

	private Payment payment;

	@BeforeEach
	void setUp() {
		var member = memberRepository.save(MemberFixture.create());
		var order = orderRepository.save(OrderFixture.create(member));
		payment = paymentRepository.save(Payment.create(member, order));
	}

	@Test
	@DisplayName("startApprovalInNewTransaction으로 IN_PROGRESS attempt를 저장하고 paymentKey는 attempt에만 남긴다")
	void startApprovalInNewTransaction_persists() {
		// when
		Long attemptDbId = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-abc", "toss-key")
			.attemptDbId();

		// then
		PaymentAttempt saved = paymentAttemptRepository.findById(attemptDbId).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(saved.getAttemptId()).isEqualTo("attempt-abc");
		assertThat(saved.getPaymentKey()).isEqualTo("toss-key");
		// 옵션 3: Payment.paymentKey는 승인 확정 시에만 저장. TX A에서는 null 유지.
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isNull();
	}

	@Test
	@DisplayName("같은 결제에 서로 다른 attemptId로 동시에 요청하면 승인 시도 하나만 생성된다")
	void concurrent_attempts_create_only_one_approval() throws Exception {
		// given
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> startTogether("first", ready, start));
			var second = executor.submit(() -> startTogether("second", ready, start));
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				// when
				start.countDown();
			}
			var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

			// then
			assertThat(outcomes)
				.containsExactlyInAnyOrder("started", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS.name());
			assertThat(jdbcTemplate.queryForObject(
				"select count(*) from payment_attempt where payment_id = ?",
				Long.class, payment.getId())).isEqualTo(1);
			// 옵션 3: Payment.paymentKey는 승인 확정 시에만 저장. TX A에서는 PaymentAttempt에만 저장.
			assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isNull();
		}
	}

	@Test
	@DisplayName("진행 중인 승인에 다른 attemptId로 재요청하면 새 시도는 거절되고 Payment.paymentKey는 null 유지")
	void rejects_new_attempt_while_in_progress() {
		// given
		paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when / then
		assertThatThrownBy(() -> paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "second", "second-key"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		// 옵션 3: Payment.paymentKey는 승인 확정 시에만 저장. TX A에서는 null 유지.
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isNull();
		assertThat(paymentAttemptRepository.findByAttemptId("second")).isEmpty();
	}

	@Test
	@DisplayName("이전 시도가 FAILED면 다른 카드로 새 승인을 시작할 수 있다")
	void allows_new_attempt_after_previous_failed() {
		// given: 이전 결제 시도가 FAILED 상태
		PaymentAttempt failed = PaymentAttempt.startApproval(payment.getId(), "first", "first-key");
		failed.markFailed("REJECT", "카드 거절");
		paymentAttemptRepository.save(failed);

		// when: 새 paymentKey/attemptId로 재시도
		PaymentAttemptStartResult second = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "second", "second-key");

		// then
		assertThat(second.created()).isTrue();
		assertThat(paymentAttemptRepository.findByAttemptId("second")).isPresent();
	}

	@Test
	@DisplayName("같은 승인 시도를 다시 시작하면 기존 ID를 반환하고 새 실행 권한을 주지 않는다")
	void existing_attempt_does_not_grant_execution_again() {
		// given
		PaymentAttemptStartResult first = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when
		PaymentAttemptStartResult second = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// then
		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.attemptDbId()).isEqualTo(first.attemptDbId());
	}

	@Test
	@DisplayName("같은 attemptId의 paymentKey를 변경하면 요청을 거절한다")
	void existing_attempt_rejects_changed_key_under_lock() {
		// given
		paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when / then
		assertThatThrownBy(() -> paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "first", "changed-key"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_REQUEST_MISMATCH);
	}

	@Test
	@DisplayName("markFailedInNewTransaction으로 attempt를 FAILED로 전환하고 에러 정보를 기록한다")
	void markFailedInNewTransaction_transitions() {
		// given: IN_PROGRESS attempt를 직접 저장
		PaymentAttempt inProgress = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), "attempt-abc", "toss-key"));

		// when
		paymentAttemptManager.markFailedInNewTransaction(
			payment.getId(), inProgress.getId(), new AttemptError("REJECT", "카드 거절"));

		// then
		PaymentAttempt updated = paymentAttemptRepository.findById(inProgress.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(updated.getErrorCode()).isEqualTo("REJECT");
		assertThat(updated.getErrorMessage()).isEqualTo("카드 거절");
	}

	@Test
	@DisplayName("승인 확정이 Payment를 잠근 채 attempt를 SUCCEEDED로 바꾸는 중에 실패 처리가 들어오면 확정 커밋을 기다린 뒤 SUCCEEDED를 덮어쓰지 않는다")
	void markFailed_waits_for_finalizer_and_keeps_succeeded() throws Exception {
		// given
		PaymentAttempt attempt = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), "attempt-race", "toss-key"));
		CountDownLatch finalizerFlushed = new CountDownLatch(1);
		CountDownLatch releaseFinalizer = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<?> finalizer = executor.submit(() -> tx.executeWithoutResult(status -> {
				paymentRepository.findByIdForUpdate(payment.getId()).orElseThrow();
				PaymentAttempt inTx = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
				inTx.markSucceeded();
				entityManager.flush();
				finalizerFlushed.countDown();
				awaitOrFail(releaseFinalizer);
			}));
			assertThat(finalizerFlushed.await(5, TimeUnit.SECONDS)).isTrue();

			Future<?> failer = executor.submit(() -> paymentAttemptManager.markFailedInNewTransaction(
				payment.getId(), attempt.getId(), new AttemptError("REJECT", "카드 거절")));
			assertThatThrownBy(() -> failer.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

			// when
			releaseFinalizer.countDown();
			finalizer.get(10, TimeUnit.SECONDS);
			failer.get(10, TimeUnit.SECONDS);
		}

		// then
		PaymentAttempt result = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(result.getErrorCode()).isNull();
	}

	@Test
	@DisplayName("attempt가 요청한 paymentId 소유가 아니면 실패 처리를 거절하고 attempt 상태를 바꾸지 않는다")
	void markFailed_rejects_attempt_owned_by_different_payment() {
		// given: 다른 Payment 소유의 IN_PROGRESS attempt
		var otherMember = memberRepository.save(MemberFixture.createOther());
		var otherOrder = orderRepository.save(OrderFixture.create(otherMember));
		Payment otherPayment = paymentRepository.save(Payment.create(otherMember, otherOrder));
		PaymentAttempt otherAttempt = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(otherPayment.getId(), "attempt-other", "toss-key"));

		// when / then: 이번 payment의 잠금으로 다른 payment 소유 attempt를 실패 처리하려 하면 거절된다
		assertThatThrownBy(() -> paymentAttemptManager.markFailedInNewTransaction(
			payment.getId(), otherAttempt.getId(), new AttemptError("REJECT", "카드 거절")))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_ATTEMPT_REQUEST_MISMATCH.getMessage());

		PaymentAttempt unchanged = paymentAttemptRepository.findById(otherAttempt.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("markReviewRequiredInNewTransaction으로 IN_PROGRESS attempt를 REVIEW_REQUIRED로 바꾸고 사유를 기록한다")
	void markReviewRequiredInNewTransaction_transitions() {
		// given
		PaymentAttempt inProgress = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), "attempt-review", "toss-key"));

		// when
		paymentAttemptManager.markReviewRequiredInNewTransaction(
			payment.getId(), inProgress.getId(), new AttemptError("REVIEW_DEPARTED", "출발 후 승인"));

		// then
		PaymentAttempt updated = paymentAttemptRepository.findById(inProgress.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.REVIEW_REQUIRED);
		assertThat(updated.getErrorCode()).isEqualTo("REVIEW_DEPARTED");
		assertThat(updated.getErrorMessage()).isEqualTo("출발 후 승인");
	}

	@Test
	@DisplayName("이미 SUCCEEDED인 attempt에 수동 확인 전이를 요청하면 아무것도 바꾸지 않는다")
	void markReviewRequiredInNewTransaction_is_noop_for_succeeded() {
		// given
		PaymentAttempt succeeded = PaymentAttempt.startApproval(payment.getId(), "attempt-done", "toss-key");
		succeeded.markSucceeded();
		PaymentAttempt saved = paymentAttemptRepository.save(succeeded);

		// when
		paymentAttemptManager.markReviewRequiredInNewTransaction(
			payment.getId(), saved.getId(), new AttemptError("REVIEW_SEAT_LOST", "좌석 충돌"));

		// then
		PaymentAttempt result = paymentAttemptRepository.findById(saved.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(result.getErrorCode()).isNull();
	}

	private static void awaitOrFail(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("승인 확정 스레드 대기 시간 초과");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@Test
	@DisplayName("최근 승인 시도가 수동 확인 대상이면 다른 카드로 시작한 새 승인은 PAYMENT_ATTEMPT_REVIEW_REQUIRED 예외로 거절된다")
	void rejects_new_attempt_when_latest_is_review_required() {
		// given
		PaymentAttempt reviewed = PaymentAttempt.startApproval(payment.getId(), "first", "first-key");
		reviewed.markReviewRequired("REVIEW_SEAT_LOST", "좌석 충돌");
		paymentAttemptRepository.save(reviewed);

		// when & then
		assertThatThrownBy(() -> paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), "second", "second-key"))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_ATTEMPT_REVIEW_REQUIRED.getMessage());
		assertThat(paymentAttemptRepository.findByAttemptId("second")).isEmpty();
	}

	private String startTogether(String attemptId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new AssertionError("동시 요청 시작 대기 시간 초과");
		}
		try {
			paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), attemptId, attemptId + "-key");
			return "started";
		} catch (BusinessException e) {
			return ((PaymentError) e.getErrorCode()).name();
		}
	}
	@Test
	@DisplayName("같은 NOT_SENT attempt에 동시에 재진입하면 하나만 성공한다")
	void reopenInNewTransaction_concurrent_onlyOneSucceeds() throws Exception {
		// given
		Long attemptDbId = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-reopen", "toss-key")
			.attemptDbId();
		paymentAttemptManager.markNotSentInNewTransaction(
			payment.getId(), attemptDbId, new AttemptError("CONFIRM_NOT_SENT", "전송되지 않음"));

		int threads = 2;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger succeeded = new AtomicInteger();
		AtomicReference<ErrorCode> loserError = new AtomicReference<>();
		AtomicReference<Exception> unexpectedError = new AtomicReference<>();
		ExecutorService pool = Executors.newFixedThreadPool(threads);

		// when
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					start.await();
					paymentAttemptManager.reopenInNewTransaction(payment.getId(), attemptDbId);
					succeeded.incrementAndGet();
				} catch (BusinessException expectedForLoser) {
					// 진 쪽은 다른 요청이 이미 진행 중이라는 뜻의 409를 받아야 한다
					loserError.set(expectedForLoser.getErrorCode());
				} catch (Exception unexpected) {
					unexpectedError.set(unexpected);
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		done.await();
		pool.shutdown();

		// then
		assertThat(unexpectedError.get()).as("진 쪽이 예상 밖 예외를 받으면 안 된다").isNull();
		assertThat(succeeded.get()).isEqualTo(1);
		assertThat(loserError.get()).isEqualTo(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		assertThat(paymentAttemptRepository.findById(attemptDbId).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
	}
	@Test
	@DisplayName("Payment가 이미 PAID면 NOT_SENT attempt를 재개할 수 없다")
	void reopenInNewTransaction_rejectedWhenPaymentAlreadyPaid() {
		// given
		Long attemptDbId = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-paid", "toss-key")
			.attemptDbId();
		paymentAttemptManager.markNotSentInNewTransaction(
			payment.getId(), attemptDbId, new AttemptError("CONFIRM_NOT_SENT", "전송되지 않음"));
		Payment paid = paymentRepository.findById(payment.getId()).orElseThrow();
		paid.approve(PaymentMethod.CREDIT_CARD, "toss-key");
		paymentRepository.save(paid);

		// when & then
		assertThatThrownBy(() -> paymentAttemptManager.reopenInNewTransaction(payment.getId(), attemptDbId))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ALREADY_COMPLETED);
	}

	@Test
	@DisplayName("다른 attempt가 진행 중이면 NOT_SENT attempt를 재개할 수 없다")
	void reopenInNewTransaction_rejectedWhenAnotherAttemptInProgress() {
		// given
		Long notSentId = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-old", "toss-key-1")
			.attemptDbId();
		paymentAttemptManager.markNotSentInNewTransaction(
			payment.getId(), notSentId, new AttemptError("CONFIRM_NOT_SENT", "전송되지 않음"));
		// 사용자가 새 결제창에서 다른 paymentKey로 다시 시작한 상황
		paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), "attempt-new", "toss-key-2");

		// when & then
		assertThatThrownBy(() -> paymentAttemptManager.reopenInNewTransaction(payment.getId(), notSentId))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
	}

	@Test
	@DisplayName("최근 attempt가 NOT_SENT면 다른 attemptId의 새 시도를 허용한다")
	void startApprovalInNewTransaction_allowedWhenLatestIsNotSent() {
		// given
		Long notSentId = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-old", "toss-key-1")
			.attemptDbId();
		paymentAttemptManager.markNotSentInNewTransaction(
			payment.getId(), notSentId, new AttemptError("CONFIRM_NOT_SENT", "전송되지 않음"));

		// when
		PaymentAttemptStartResult result = paymentAttemptManager
			.startApprovalInNewTransaction(payment.getId(), "attempt-new", "toss-key-2");

		// then
		assertThat(result.created()).isTrue();
		assertThat(paymentAttemptRepository.findById(result.attemptDbId()).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
	}
}
