package com.sudo.raillo.payment.application;

/** 결제 시도를 실패나 수동 확인으로 끝낼 때 남기는 사유. */
public record AttemptError(String code, String message) {
}
