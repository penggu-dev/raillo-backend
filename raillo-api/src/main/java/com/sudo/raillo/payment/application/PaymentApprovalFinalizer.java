package com.sudo.raillo.payment.application;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.BookingCreator;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Toss 승인 성공 결과를 로컬 DB에 원자적으로 확정한다.
 *
 * <p>외부 API 호출 전에 조회한 엔티티는 재사용하지 않는다. 별도 트랜잭션에서 Payment를 다시 잠그고 Order·PaymentAttempt를 최신 상태로 조회한 뒤 Order/Booking/Payment/Attempt/Outbox를 함께 커밋한다.
 *
 * <p><b>3층 재검증 구조 중 마지막 층(TX B).</b> Toss 응답 대기 사이 상태가 바뀌었을 가능성을 pre-check({@link PaymentApprovalStarter})와 TX A({@link PaymentAttemptManager})에서 통과한 검증들로 최신 커밋 상태 기준으로 다시 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApprovalFinalizer {

	private final PaymentRepository paymentRepository;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentOutboxRepository paymentOutboxRepository;
	private final OrderReader orderReader;
	private final BookingCreator bookingCreator;
	private final PaymentValidator paymentValidator;
	private final ObjectMapper objectMapper;

	@Transactional
	public PaymentConfirmResult finalizeApproval(
		Long paymentId,
		Long attemptDbId,
		PaymentConfirmCommand command,
		GatewayConfirmResult gatewayResult
	) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		Order order = orderReader.getOrderByOrderCode(command.orderId());
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));

		paymentValidator.validateApprovalAttempt(attempt, paymentId, command.paymentKey());

		// 확정은 아래 TX B가 Order와 Payment와 Booking과 Attempt와 Outbox를 한 트랜잭션으로 커밋하는 것. 이 switch는 그 전의 상태별 조기 종료다.
		// enum switch 문은 exhaustive 강제가 없어 모든 상태를 직접 열거하고 default로 막는다. 빠진 상태가 조용히 확정 경로로 떨어지는 것이 가장 위험하다.
		switch (attempt.getStatus()) {
			// 최초 confirm과 사용자 재시도의 상태 재조회가 같은 attempt에 대해 동시에 TX B에 진입한 경우,
			// 먼저 잠금을 얻은 쪽이 이미 SUCCEEDED로 확정했다면 실패로 응답하지 않고 이전 결과를 그대로 돌려준다.
			case SUCCEEDED -> {
				log.info("[결제 확정 - 동시 요청이 먼저 확정] attemptId={}, paymentId={}", attempt.getAttemptId(), paymentId);
				return PaymentConfirmResult.from(payment);
			}
			// Worker가 먼저 수동 확인 대상으로 바꾼 attempt는 자동으로 확정하지 않는다.
			case REVIEW_REQUIRED -> {
				log.warn("[결제 확정 거절 - 수동 확인 대상] attemptId={}, paymentId={}, errorCode={}",
					attempt.getAttemptId(), paymentId, attempt.getErrorCode());
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_REVIEW_REQUIRED);
			}
			// Toss는 승인해 카드가 청구됐는데 기록만 FAILED라 예매가 없는 상태. Recovery Worker는 IN_PROGRESS만 집어 아무도 다시 보지 않음
			// TODO(#270): 탐지와 알림만 수행. markReviewRequired가 IN_PROGRESS에서만 허용돼 이 레코드를 되살릴 경로 없음
			case FAILED -> {
				log.error("[결제 확정 위험 - 이미 FAILED인 attempt에 승인 확정 진입] paymentKey={}, attemptId={}, paymentId={}, "
						+ "돈이 이미 빠져나갔을 수 있으나 attempt는 FAILED이고 예매는 생성되지 않았습니다.",
					command.paymentKey(), attempt.getAttemptId(), paymentId);
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
			}
			// 정상 경로. 조기 종료 없이 switch 아래 확정 로직으로 진행
			case IN_PROGRESS -> { }
			// 승인 호출 미발송이라 확정할 승인이 없음. 호출 결과와 attempt 상태의 불일치이므로 즉시 차단
			case NOT_SENT -> {
				log.error("[결제 확정 거절 - 전송되지 않은 attempt] attemptId={}, paymentId={}",
					attempt.getAttemptId(), paymentId);
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
			}
			default -> throw new IllegalStateException(
				"처리 규칙이 정해지지 않은 PaymentAttemptStatus: " + attempt.getStatus());
		}

		paymentValidator.validateApprovable(payment);
		paymentValidator.validateDuplicatePayment(order);
		paymentValidator.validateGatewayResponseMatchesRequest(gatewayResult, command);

		order.completePayment();
		var confirmed = bookingCreator.createBookingFromOrder(order);
		payment.approve(gatewayResult.method(), gatewayResult.paymentKey());
		attempt.markSucceeded();

		List<Reservation> reservations = orderReader.getReservationSnapshots(order);
		paymentOutboxRepository.save(buildBookingConfirmedOutbox(BookingConfirmedPayload.from(paymentId, attempt, reservations, confirmed)));
		return PaymentConfirmResult.from(payment);
	}

	private PaymentOutbox buildBookingConfirmedOutbox(BookingConfirmedPayload payload) {
		try {
			String payloadJson = objectMapper.writeValueAsString(payload);
			return PaymentOutbox.forBookingConfirmed(payload.paymentId(), payloadJson);
		} catch (JacksonException e) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_SERIALIZATION_FAILED);
		}
	}
}
