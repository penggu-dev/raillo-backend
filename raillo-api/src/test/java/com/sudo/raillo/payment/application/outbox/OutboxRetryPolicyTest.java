package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.*;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.exception.DomainException;
import com.sudo.raillo.global.redis.exception.RedisException;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

	private final OutboxProperties props = new OutboxProperties(
		Duration.ofSeconds(5),
		10,
		Duration.ofSeconds(1),
		Duration.ofMinutes(1),
		5
	);
	private final OutboxRetryPolicy policy = new OutboxRetryPolicy(props);

	@Test
	@DisplayName("첫 재시도의 다음 실행 시각은 initialBackoff만큼 뒤")
	void nextRetryAt_firstRetry_appliesInitialBackoff() {
		// given
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		// when
		LocalDateTime next = policy.nextRetryAt(now, 0);

		// then
		assertThat(next).isEqualTo(now.plusSeconds(1));
	}

	@Test
	@DisplayName("재시도 횟수마다 2배씩 backoff가 증가한다")
	void nextRetryAt_exponentialBackoff() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		assertThat(policy.nextRetryAt(now, 1)).isEqualTo(now.plusSeconds(2));
		assertThat(policy.nextRetryAt(now, 2)).isEqualTo(now.plusSeconds(4));
		assertThat(policy.nextRetryAt(now, 3)).isEqualTo(now.plusSeconds(8));
	}

	@Test
	@DisplayName("maxBackoff를 초과하지 않는다")
	void nextRetryAt_cappedAtMax() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0, 0);

		LocalDateTime next = policy.nextRetryAt(now, 20);

		assertThat(next).isEqualTo(now.plusMinutes(1));
	}

	@Test
	@DisplayName("재시도 횟수가 maxRetries 이상이면 포기한다")
	void shouldGiveUp_afterMaxRetries() {
		assertThat(policy.shouldGiveUp(4)).isFalse();
		assertThat(policy.shouldGiveUp(5)).isTrue();
		assertThat(policy.shouldGiveUp(6)).isTrue();
	}

	@Test
	@DisplayName("BusinessException이 아닌 실패는 모두 재시도 대상이다")
	void isRetryable_nonBusinessException() {
		assertThat(policy.isRetryable(new RuntimeException("redis timeout"))).isTrue();
		assertThat(policy.isRetryable(new IllegalStateException("contract"))).isTrue();
	}

	@Test
	@DisplayName("재시도 가능을 선언한 에러 코드는 재시도 대상이다")
	void isRetryable_retryableErrorCode() {
		assertThat(policy.isRetryable(
			new BusinessException(PaymentError.PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT))).isTrue();
		assertThat(policy.isRetryable(
			new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR))).isTrue();
	}

	@Test
	@DisplayName("재시도 불가를 선언한 에러 코드만 재시도 대상에서 빠진다")
	void isRetryable_nonRetryableErrorCode() {
		assertThat(policy.isRetryable(
			new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED))).isFalse();
	}

	@Test
	@DisplayName("에러 코드를 싣는 예외는 클래스가 달라도 같게 판정한다")
	void isRetryable_dispatchesOnCarrierNotOnExceptionClass() {
		// 에러 코드를 싣는 예외가 셋이고 공통 부모가 RuntimeException뿐이라, 한 클래스만 보면 나머지가 조용히 빠진다
		assertThat(policy.isRetryable(
			new DomainException(BookingError.SEAT_OCCUPANCY_CORRUPTED))).isFalse();
		assertThat(policy.isRetryable(
			new RedisException(BookingError.SEAT_OCCUPANCY_CORRUPTED))).isFalse();

		assertThat(policy.isRetryable(
			new DomainException(PaymentError.PAYMENT_OUTBOX_NOT_TRANSITIONABLE))).isTrue();
		assertThat(policy.isRetryable(
			new RedisException(PaymentError.PAYMENT_OUTBOX_NOT_TRANSITIONABLE))).isTrue();
	}
}
