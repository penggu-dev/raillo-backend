package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentAttemptStartResult;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
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
		paymentAttemptManager.markFailedInNewTransaction(inProgress.getId(), "REJECT", "카드 거절");

		// then
		PaymentAttempt updated = paymentAttemptRepository.findById(inProgress.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(updated.getErrorCode()).isEqualTo("REJECT");
		assertThat(updated.getErrorMessage()).isEqualTo("카드 거절");
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
}
