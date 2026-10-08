package com.sudo.raillo.payment.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 게이트웨이 호출이 어디까지 갔는지. 실패를 어떻게 기록할지가 이 값으로 갈린다.
 *
 * <p>저장되지 않는 값이며 {@link com.sudo.raillo.payment.domain.PaymentAttemptStatus}와는 다른 축이다.
 * 이 값은 호출 한 번의 도달 여부를 나타내고, attempt 상태는 결제 시도의 영속 상태를 나타낸다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum DeliveryPhase {

	NOT_REACHED("게이트웨이 미도달 확정, 재시도해도 중복 처리 위험 없음"),
	NO_RESPONSE("요청 전송 후 결과 미확인, 재시도 시 중복 처리 위험"),
	ANSWERED("게이트웨이 HTTP 응답 수신, 상태 코드로 확정 여부 판정");

	private final String description;
}
