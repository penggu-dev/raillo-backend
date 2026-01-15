package com.sudo.raillo.global.event.domain;

public enum EventStatus {
	PROGRESS,  // 처리중
	COMPLETED, // 처리 완료
	RETRY,     // 재시도 대기중
	FAILED     // 처리 실패
}
