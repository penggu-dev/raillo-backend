package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;

/**
 * {@code @Enumerated(EnumType.STRING)} 단독은 MySQL 네이티브 ENUM 컬럼을 만들고, 그러면 enum 값 추가가
 * {@code ddl-auto: update}로 반영되지 않는다. 매핑이 되돌아가면 이 테스트가 먼저 알린다.
 */
@ServiceTest
@DisplayName("payment_outbox의 type과 status 컬럼")
class PaymentOutboxTypeColumnTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PaymentOutboxRepository paymentOutboxRepository;

	private String dataTypeOf(String columnName) {
		return jdbcTemplate.queryForObject("""
			select data_type from information_schema.columns
			 where table_schema = database() and table_name = 'payment_outbox' and column_name = ?
			""", String.class, columnName);
	}

	@Test
	@DisplayName("type 컬럼은 MySQL ENUM이 아니라 VARCHAR로 만들어진다")
	void type_column_is_varchar() {
		// given - Testcontainers가 엔티티 매핑으로 스키마를 만든다

		// when
		String dataType = dataTypeOf("type");

		// then
		assertThat(dataType).isEqualToIgnoringCase("varchar");
	}

	@Test
	@DisplayName("status 컬럼은 MySQL ENUM이 아니라 VARCHAR로 만들어진다")
	void status_column_is_varchar() {
		// given - Testcontainers가 엔티티 매핑으로 스키마를 만든다

		// when
		String dataType = dataTypeOf("status");

		// then
		assertThat(dataType).isEqualToIgnoringCase("varchar");
	}

	/** {@code information_schema}가 컬럼을 알려주지 않으므로 절 안의 컬럼 이름으로 고른다. */
	private String checkClauseOf(String columnName) {
		List<String> clauses = jdbcTemplate.queryForList("""
			select cc.check_clause
			  from information_schema.check_constraints cc
			  join information_schema.table_constraints tc
			    on tc.constraint_schema = cc.constraint_schema
			   and tc.constraint_name = cc.constraint_name
			 where tc.table_schema = database() and tc.table_name = 'payment_outbox'
			""", String.class);
		String needle = "`" + columnName + "` in";
		return clauses.stream()
			.filter(clause -> clause.contains(needle))
			.reduce((a, b) -> {
				throw new IllegalStateException("%s 컬럼의 CHECK 절이 둘 이상이다".formatted(columnName));
			})
			.orElseThrow(() -> new AssertionError(
				"%s 컬럼의 CHECK 절이 없다. 조회된 절: %s".formatted(columnName, clauses)));
	}

	/**
	 * VARCHAR로 바꿔도 값 목록이 CHECK 제약으로 남는다는 사실을 고정한다.
	 *
	 * <p>다음 enum 값 추가를 경고하지는 못한다. 테스트 스키마는 매번 매핑으로 다시 만들어져 CHECK에
	 * 그때의 값이 모두 들어간다. 깨지는 환경은 값 추가 이전에 매핑으로 만들어진 스키마이고, 그쪽은
	 * {@code MODIFY}가 아니라 {@code DROP CHECK}가 필요하다. 상세는
	 * {@code docs/payment-data-contracts.md}에 있다.</p>
	 */
	@Test
	@DisplayName("VARCHAR로 바뀌어도 Hibernate가 type의 값 목록을 CHECK 제약으로 만든다")
	void type_values_remain_in_a_check_constraint() {
		// given - Testcontainers가 엔티티 매핑으로 스키마를 만든다

		// when
		String clause = checkClauseOf("type");

		// then
		for (PaymentOutboxType type : PaymentOutboxType.values()) {
			assertThat(clause)
				.as("type의 CHECK 절이 %s 를 담고 있어야 한다", type)
				.contains(type.name());
		}
	}

	@Test
	@DisplayName("VARCHAR로 바뀌어도 Hibernate가 status의 값 목록을 CHECK 제약으로 만든다")
	void status_values_remain_in_a_check_constraint() {
		// given - Testcontainers가 엔티티 매핑으로 스키마를 만든다

		// when
		String clause = checkClauseOf("status");

		// then
		for (PaymentOutboxStatus status : PaymentOutboxStatus.values()) {
			assertThat(clause)
				.as("status의 CHECK 절이 %s 를 담고 있어야 한다", status)
				.contains(status.name());
		}
	}

	@Test
	@DisplayName("BOOKING_SEAT_RELEASE_REQUIRED 타입을 저장하고 다시 읽을 수 있다")
	void seat_release_type_round_trips() {
		// given
		PaymentOutbox outbox = PaymentOutbox.forBookingSeatRelease(77L, "{}");

		// when
		PaymentOutbox saved = paymentOutboxRepository.save(outbox);

		// then
		String stored = jdbcTemplate.queryForObject(
			"select type from payment_outbox where payment_outbox_id = ?", String.class, saved.getId());
		assertThat(stored).isEqualTo("BOOKING_SEAT_RELEASE_REQUIRED");
		assertThat(paymentOutboxRepository.findById(saved.getId()).orElseThrow().getType())
			.isEqualTo(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED);
	}
}
