package com.sudo.raillo.event.domain;

public enum OutboxStatus {
	PENDING,      // 생성됨, 즉시 처리 대기
	PROCESSING,   // 배치 처리 중 (파드가 선점)
	COMPLETED,    // 처리 완료
	FAILED,       // 실패 (재시도 대상)
	DEAD          // 최종 실패 (보상 트랜잭션 필요)
}
