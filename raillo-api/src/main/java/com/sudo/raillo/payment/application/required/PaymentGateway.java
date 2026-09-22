package com.sudo.raillo.payment.application.required;

import java.math.BigDecimal;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.PaymentMethod;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 외부 결제 게이트웨이 required port.
 *
 * <p>토스페이먼츠 등 PG 세부사항은 어댑터에 격리하고, 애플리케이션은 도메인 중립 결과만 다룬다.
 */
public interface PaymentGateway {

	GatewayConfirmResult confirm(PaymentConfirmCommand command);

	/**
	 * 게이트웨이의 결제 상태를 조회한다. Toss 응답 유실로 PaymentAttempt가 IN_PROGRESS로 남았을 때 사용자가 재시도하면 이 조회 결과로 로컬을 정정한다. 확정 상태(DONE)이면 {@link GatewayQueryResult#confirmResult()}에 승인 결과가 담긴다.
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
	@Getter
	@RequiredArgsConstructor
	enum GatewayPaymentStatus {
		READY("결제 생성 초기 상태, 인증 전"),
		IN_PROGRESS("결제수단 인증 완료, 승인 API 호출 대기"),
		WAITING_FOR_DEPOSIT("가상계좌 발급 후 구매자 입금 대기"),
		DONE("승인 완료"),
		CANCELED("승인된 결제가 취소됨(가상계좌 입금 전 취소 포함)"),
		PARTIAL_CANCELED("승인된 결제가 부분 취소됨"),
		ABORTED("승인 실패"),
		EXPIRED("유효 시간 30분 경과로 자동 취소"),
		UNKNOWN("매핑되지 않은 게이트웨이 상태(방어용)");

		private final String description;
	}
}
