package com.sudo.raillo.global.event.domain;

public enum OutboxStatus {
	PENDING,   // 처리 대기
	COMPLETED, // 처리 완료
	FAILED     // 처리 실패
}
