package com.sudo.raillo.payment.application.required;

/**
 * 복구 Worker가 판정과 확정 재실행 시점에 호출하는 관측 지표 required port.
 */
public interface RecoveryMetrics {

	void incrementDecision(String decision);

	void incrementRecovered();
}
