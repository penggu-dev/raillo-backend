package com.sudo.raillo.payment.application.required;

import java.math.BigDecimal;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.PaymentMethod;

/**
 * 외부 결제 게이트웨이 required port.
 *
 * <p>토스페이먼츠 등 PG 세부사항은 어댑터에 격리하고, 애플리케이션은 도메인 중립 결과만 다룬다.
 */
public interface PaymentGateway {

	GatewayConfirmResult confirm(PaymentConfirmCommand command);

	/**
	 * 게이트웨이의 결제 상태를 조회한다.
	 *
	 * <p>Toss 응답이 유실돼 PaymentAttempt가 IN_PROGRESS로 남았을 때, 사용자가 결제창에서
	 * 재시도하면 이 조회 결과로 로컬 DB를 정정한다. 확정 상태(DONE)이면
	 * {@link GatewayQueryResult#confirmResult()}에 승인 결과가 담긴다.
	 */
	GatewayQueryResult query(String paymentKey);

	record GatewayConfirmResult(
		String paymentKey,
		String orderCode,
		BigDecimal totalAmount,
		PaymentMethod method
	) {
	}

	record GatewayQueryResult(
		GatewayPaymentStatus status,
		GatewayConfirmResult confirmResult
	) {
		public static GatewayQueryResult of(GatewayPaymentStatus status) {
			return new GatewayQueryResult(status, null);
		}

		public static GatewayQueryResult done(GatewayConfirmResult confirmResult) {
			return new GatewayQueryResult(GatewayPaymentStatus.DONE, confirmResult);
		}
	}

	/**
	 * 결제 게이트웨이가 보고하는 결제 상태. 토스 결제조회 API의 status 값을 도메인 중립 표현으로 매핑한다.
	 */
	enum GatewayPaymentStatus {
		READY,
		IN_PROGRESS,
		WAITING_FOR_DEPOSIT,
		DONE,
		CANCELED,
		PARTIAL_CANCELED,
		ABORTED,
		EXPIRED,
		UNKNOWN
	}
}
