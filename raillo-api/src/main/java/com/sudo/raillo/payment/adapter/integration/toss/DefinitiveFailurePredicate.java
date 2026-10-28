package com.sudo.raillo.payment.adapter.integration.toss;

import java.util.function.Predicate;

import com.sudo.raillo.payment.application.exception.PaymentGatewayException;

/**
 * 브레이커 창에서 제외할 실패. 게이트웨이가 4xx로 확정 응답한 것만 뺀다.
 *
 * <p>카드 거절이 몰려도 게이트웨이는 멀쩡하므로 회로를 열면 안 된다. 조회의 404도 같다.
 * YAML의 {@code ignore-exception-predicate}가 이 클래스를 이름으로 참조하므로 기본 생성자가 필요하다.</p>
 */
public class DefinitiveFailurePredicate implements Predicate<Throwable> {

	@Override
	public boolean test(Throwable throwable) {
		return throwable instanceof PaymentGatewayException failure && failure.isDefinitiveFailure();
	}
}
