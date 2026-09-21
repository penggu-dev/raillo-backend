package com.sudo.raillo.payment.adapter.integration.toss;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

	private final TossPaymentClient tossPaymentClient;

	@Override
	public GatewayConfirmResult confirm(PaymentConfirmCommand command) {
		TossPaymentConfirmResponse response = tossPaymentClient.confirmPayment(command);
		return new GatewayConfirmResult(
			response.paymentKey(),
			response.orderId(),
			BigDecimal.valueOf(response.totalAmount()),
			mapMethod(response.method())
		);
	}

	@Override
	public GatewayQueryResult query(String paymentKey) {
		TossPaymentQueryResponse response = tossPaymentClient.queryPayment(paymentKey);
		GatewayPaymentStatus status = mapStatus(response.status());
		if (status == GatewayPaymentStatus.DONE) {
			return GatewayQueryResult.done(new GatewayConfirmResult(
				response.paymentKey(),
				response.orderId(),
				BigDecimal.valueOf(response.totalAmount()),
				mapMethod(response.method())
			));
		}
		return GatewayQueryResult.of(status);
	}

	private GatewayPaymentStatus mapStatus(String tossStatus) {
		if (tossStatus == null) {
			return GatewayPaymentStatus.UNKNOWN;
		}
		return switch (tossStatus) {
			case "READY" -> GatewayPaymentStatus.READY;
			case "IN_PROGRESS" -> GatewayPaymentStatus.IN_PROGRESS;
			case "WAITING_FOR_DEPOSIT" -> GatewayPaymentStatus.WAITING_FOR_DEPOSIT;
			case "DONE" -> GatewayPaymentStatus.DONE;
			case "CANCELED" -> GatewayPaymentStatus.CANCELED;
			case "PARTIAL_CANCELED" -> GatewayPaymentStatus.PARTIAL_CANCELED;
			case "ABORTED" -> GatewayPaymentStatus.ABORTED;
			case "EXPIRED" -> GatewayPaymentStatus.EXPIRED;
			default -> {
				log.warn("[TOSS] 알 수 없는 결제 상태: {}", tossStatus);
				yield GatewayPaymentStatus.UNKNOWN;
			}
		};
	}

	private PaymentMethod mapMethod(String tossMethod) {
		if (tossMethod == null) {
			// Toss 조회 응답의 method는 결제 미완료 상태에서 null이 될 수 있다.
			// DONE 응답에는 실무상 값이 오지만, 스펙이 nullable이므로 방어 코드를 유지한다.
			log.warn("[TOSS] 결제 수단이 null로 응답됨");
			throw new BusinessException(PaymentError.INVALID_PAYMENT_METHOD, "결제 수단 정보가 응답에 없습니다.");
		}
		return switch (tossMethod) {
			case "카드" -> PaymentMethod.CREDIT_CARD;
			case "가상계좌" -> PaymentMethod.VIRTUAL_ACCOUNT;
			case "계좌이체" -> PaymentMethod.TRANSFER;
			case "휴대폰" -> PaymentMethod.MOBILE_PHONE;
			case "간편결제" -> PaymentMethod.EASY_PAY;
			case "문화상품권", "도서문화상품권", "게임문화상품권" -> PaymentMethod.GIFT_CERTIFICATE;
			default -> {
				log.warn("알 수 없는 결제수단: {}", tossMethod);
				throw new BusinessException(PaymentError.INVALID_PAYMENT_METHOD,
					"지원하지 않는 결제 수단입니다: " + tossMethod);
			}
		};
	}
}
