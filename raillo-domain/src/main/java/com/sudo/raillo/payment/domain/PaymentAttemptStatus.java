package com.sudo.raillo.payment.domain;

public enum PaymentAttemptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    REVIEW_REQUIRED,
    /** 요청이 게이트웨이에 도달하지 않은 것이 확정. 비종결이며, 사용자가 같은 결제창에서 다시 요청하면 같은 attemptId로 이어간다. */
    NOT_SENT
}
