package com.sudo.raillo.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.global.exception.DomainException;
import com.sudo.raillo.payment.domain.exception.PaymentError;

class PaymentAttemptTest {

    @Test
    @DisplayName("승인 attempt를 IN_PROGRESS 상태로 생성한다")
    void startApproval_isInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        assertThat(attempt.getPaymentId()).isEqualTo(1L);
        assertThat(attempt.getAttemptId()).isEqualTo("attempt-abc");
        assertThat(attempt.getPaymentKey()).isEqualTo("toss-key");
        assertThat(attempt.getAttemptType()).isEqualTo(PaymentAttemptType.APPROVAL);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("IN_PROGRESS attempt를 SUCCEEDED로 전환한다")
    void markSucceeded_fromInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        attempt.markSucceeded();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("IN_PROGRESS attempt를 FAILED로 전환하고 에러 정보를 기록한다")
    void markFailed_fromInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        attempt.markFailed("REJECT_CARD_PAYMENT", "카드 승인 거절");

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(attempt.getErrorMessage()).isEqualTo("카드 승인 거절");
    }

    @Test
    @DisplayName("SUCCEEDED 상태에서 다시 markSucceeded는 도메인 예외")
    void markSucceeded_fromSucceeded_throws() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markSucceeded();

        assertThatThrownBy(attempt::markSucceeded).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("SUCCEEDED 상태에서 markFailed는 도메인 예외")
    void markFailed_fromSucceeded_throws() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markSucceeded();

        assertThatThrownBy(() -> attempt.markFailed("X", "Y")).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("IN_PROGRESS attempt를 수동 확인 대상으로 바꾸면 REVIEW_REQUIRED가 되고 사유를 기록한다")
    void markReviewRequired_fromInProgress() {
        // given
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        // when
        attempt.markReviewRequired("REVIEW_SEAT_LOST", "좌석이 다른 예매와 충돌했습니다.");

        // then
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REVIEW_REQUIRED);
        assertThat(attempt.getErrorCode()).isEqualTo("REVIEW_SEAT_LOST");
        assertThat(attempt.getErrorMessage()).isEqualTo("좌석이 다른 예매와 충돌했습니다.");
    }

    @Test
    @DisplayName("SUCCEEDED attempt를 수동 확인 대상으로 바꾸면 PAYMENT_ATTEMPT_NOT_TRANSITIONABLE 도메인 예외가 발생한다")
    void markReviewRequired_fromSucceeded_throws() {
        // given
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markSucceeded();

        // when & then
        assertThatThrownBy(() -> attempt.markReviewRequired("REVIEW_DEPARTED", "출발 후 승인"))
            .isInstanceOf(DomainException.class)
            .hasMessage(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE.getMessage());
    }

    @Test
    @DisplayName("REVIEW_REQUIRED attempt를 SUCCEEDED로 바꾸면 PAYMENT_ATTEMPT_NOT_TRANSITIONABLE 도메인 예외가 발생한다")
    void markSucceeded_fromReviewRequired_throws() {
        // given
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markReviewRequired("REVIEW_RESULT_MISMATCH", "금액 불일치");

        // when & then
        assertThatThrownBy(attempt::markSucceeded)
            .isInstanceOf(DomainException.class)
            .hasMessage(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE.getMessage());
    }
}
