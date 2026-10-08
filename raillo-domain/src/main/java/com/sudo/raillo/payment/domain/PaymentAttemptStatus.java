package com.sudo.raillo.payment.domain;

public enum PaymentAttemptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    REVIEW_REQUIRED,
    /** 요청이 게이트웨이에 도달하지 않은 것이 확정. 비종결이며 같은 attemptId로 다시 시도할 수 있다. */
    NOT_SENT
}
