package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
@DisplayName("payment_attempt.status 컬럼")
class PaymentAttemptStatusColumnTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Test
	@DisplayName("status 컬럼은 MySQL ENUM이 아니라 VARCHAR로 만들어진다")
	void status_column_is_varchar() {
		// given - Testcontainers가 엔티티 매핑으로 스키마를 만든다

		// when
		String dataType = jdbcTemplate.queryForObject("""
			select data_type from information_schema.columns
			 where table_schema = database() and table_name = 'payment_attempt' and column_name = 'status'
			""", String.class);

		// then
		assertThat(dataType).isEqualToIgnoringCase("varchar");
	}

	@Test
	@DisplayName("REVIEW_REQUIRED 상태를 저장하고 다시 읽을 수 있다")
	void review_required_round_trips() {
		// given
		var member = memberRepository.save(MemberFixture.create());
		var order = orderRepository.save(OrderFixture.create(member));
		Payment payment = paymentRepository.save(Payment.create(member, order));
		PaymentAttempt attempt = PaymentAttempt.startApproval(payment.getId(), "attempt-review", "toss-key");
		attempt.markReviewRequired("REVIEW_SEAT_LOST", "좌석 충돌");

		// when
		PaymentAttempt saved = paymentAttemptRepository.save(attempt);

		// then
		String stored = jdbcTemplate.queryForObject(
			"select status from payment_attempt where payment_attempt_id = ?", String.class, saved.getId());
		assertThat(stored).isEqualTo("REVIEW_REQUIRED");
		assertThat(paymentAttemptRepository.findById(saved.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentAttemptStatus.REVIEW_REQUIRED);
	}
}
