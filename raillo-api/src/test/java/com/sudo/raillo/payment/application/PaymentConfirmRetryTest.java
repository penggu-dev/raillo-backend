package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static com.sudo.raillo.payment.application.PaymentAttemptIds.forApproval;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.OrderTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;

@ServiceTest
class PaymentConfirmRetryTest {

	@Autowired private PaymentConfirmer paymentConfirmer;
	@MockitoSpyBean private PaymentRepository paymentRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private PaymentAttemptRepository attemptRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OrderTestHelper orderTestHelper;
	@Autowired private TrainTestHelper trainTestHelper;
	@Autowired private TrainScheduleTestHelper scheduleTestHelper;
	@Autowired private JdbcTemplate jdbcTemplate;

	@MockitoBean private TossPaymentClient tossPaymentClient;

	private Member member;
	private String memberNo;
	private Order order;
	private Payment payment;
	private TrainScheduleResult schedule;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();
		schedule = scheduleTestHelper.createDefault(trainTestHelper.createKTX());
		order = orderTestHelper.createDefault(member, schedule).order();
		payment = paymentRepository.save(Payment.create(member, order));
	}

	@Test
	@DisplayName("같은 attemptId가 다른 payment_id의 attempt에 붙어 있으면 요청 불일치로 거절한다")
	void rejects_when_attempt_belongs_to_another_payment() {
		// given: 파생 규칙(attemptId = SHA-256(apv:paymentKey))상 클라이언트 API에서는
		//        재현할 수 없지만, DB가 오염된 상황(다른 payment 행에 같은 attemptId가 이미 저장)
		//        을 재현해 방어 계층이 동작하는지 검증한다.
		String paymentKey = "shared-key";
		String derivedAttemptId = forApproval(paymentKey);
		Long otherPaymentId = payment.getId() + 999_999L;

		attemptRepository.save(PaymentAttempt.startApproval(otherPaymentId, derivedAttemptId, paymentKey));

		// when: 오염된 attempt와 동일한 paymentKey로 승인 요청
		PaymentConfirmCommand request = new PaymentConfirmCommand(
			paymentKey, order.getOrderCode(), order.getTotalAmount());

		// then: attempt.payment_id != request payment_id → mismatch (Toss는 호출되지 않음)
		assertThatThrownBy(() -> paymentConfirmer.confirm(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage("결제 시도 정보가 요청과 일치하지 않습니다.");
		verify(tossPaymentClient, never()).confirmPayment(any());
	}

	@Test
	@DisplayName("취소용으로 저장된 attemptId를 승인 요청에 사용하면 요청 불일치로 거절한다")
	void rejects_cancellation_attempt_reused_for_approval() {
		// given: 같은 paymentKey에서 파생된 attemptId로 APPROVAL을 저장한 뒤
		//        DB에서 직접 attempt_type만 CANCELLATION으로 바꿔 시나리오를 재현한다.
		String paymentKey = "original-key";
		String derivedAttemptId = forApproval(paymentKey);

		PaymentAttempt approvalAttempt = PaymentAttempt.startApproval(payment.getId(), derivedAttemptId, paymentKey);
		approvalAttempt.markSucceeded();
		PaymentAttempt saved = attemptRepository.save(approvalAttempt);
		jdbcTemplate.update(
			"update payment_attempt set attempt_type = 'CANCELLATION' where payment_attempt_id = ?",
			saved.getId());

		// when: 같은 paymentKey로 승인 요청 (attemptId 동일, attempt_type만 CANCELLATION)
		PaymentConfirmCommand approvalRequest = new PaymentConfirmCommand(
			paymentKey, order.getOrderCode(), order.getTotalAmount());

		// then: attempt.type(CANCELLATION) != APPROVAL → mismatch
		assertThatThrownBy(() -> paymentConfirmer.confirm(approvalRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasMessage("결제 시도 정보가 요청과 일치하지 않습니다.");
		verify(tossPaymentClient, never()).confirmPayment(any());
	}

	@Test
	@DisplayName("Payment 로드 후 다른 트랜잭션이 승인을 커밋한 경우, 재요청은 최신 커밋 결과를 반환한다")
	void returns_committed_result_when_payment_was_loaded_before_approval() {
		// given: 이 payment에 IN_PROGRESS attempt가 남아있는 상황
		String paymentKey = "original-key";
		String attemptId = forApproval(paymentKey);

		PaymentAttempt inflightAttempt = attemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), attemptId, paymentKey));

		// 재요청이 findByOrder로 payment를 읽은 "직후" 다른 트랜잭션이 승인을 커밋하도록
		// 스파이 훅으로 순서를 고정한다.
		TransactionTemplate independentTx = new TransactionTemplate(transactionManager);
		independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		doAnswer(invocation -> {
			Payment loadedBeforeApproval = (Payment) ((Optional<?>) invocation.callRealMethod()).orElseThrow();

			// 별도 트랜잭션에서 승인 커밋 (첫 요청이 확정하는 시점 시뮬레이션)
			independentTx.executeWithoutResult(status -> {
				Payment committed = paymentRepository.findById(payment.getId()).orElseThrow();
				committed.approve(PaymentMethod.CREDIT_CARD, paymentKey);
				attemptRepository.findById(inflightAttempt.getId()).orElseThrow().markSucceeded();
			});

			// 이 시점의 loadedBeforeApproval는 여전히 PENDING 스냅샷
			assertThat(loadedBeforeApproval.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
			return Optional.of(loadedBeforeApproval);
		}).when(paymentRepository).findByOrder(any(Order.class));

		// when: 재요청. attemptId가 이미 있으니 handleExistingAttempt가 SUCCEEDED로 분기해야 하고
		//       그때 스냅샷 대신 DB에서 최신 결제 결과를 조회해서 반환해야 함
		PaymentConfirmCommand request = new PaymentConfirmCommand(
			paymentKey, order.getOrderCode(), order.getTotalAmount());

		PaymentConfirmResult result = paymentConfirmer.confirm(request, memberNo);

		// then: PENDING이 아닌 최신 커밋 결과가 반환되어야 함
		assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(result.paymentKey()).isEqualTo(paymentKey);
		assertThat(result.paymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
		assertThat(result.paidAt()).isNotNull();
		verify(tossPaymentClient, never()).confirmPayment(any());
	}

	private void transitionTo(PaymentAttempt attempt, PaymentAttemptStatus target) {
		switch (target) {
			case SUCCEEDED -> attempt.markSucceeded();
			case FAILED -> attempt.markFailed("REJECT", "카드 거절");
			case IN_PROGRESS -> { /* startApproval의 기본 상태 유지 */ }
		}
	}
}
