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
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
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
		GatewayConfirmResult gatewayResult,
		List<Reservation> reservations
	) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		Order order = orderReader.getOrderByOrderCode(command.orderId());
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));

		paymentValidator.validateApprovalAttempt(attempt, paymentId, command.paymentKey());

		// 최초 confirm과 사용자 재시도의 상태 재조회가 같은 attempt에 대해 동시에 TX B에 진입한 경우,
		// 먼저 잠금을 얻은 쪽이 이미 SUCCEEDED로 확정했다면 실패로 응답하지 않고 이전 결과를 그대로 돌려준다.
		if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
			log.info("[결제 확정 - 동시 요청이 먼저 확정] attemptId={}, paymentId={}", attempt.getAttemptId(), paymentId);
			return PaymentConfirmResult.from(payment);
		}

		paymentValidator.validateApprovable(payment);
		paymentValidator.validateAmounts(command.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);
		paymentValidator.validateGatewayResponseMatchesRequest(gatewayResult, command);

		order.completePayment();
		var confirmed = bookingCreator.createBookingFromOrder(order);
		payment.approve(gatewayResult.method(), gatewayResult.paymentKey());
		attempt.markSucceeded();

		paymentOutboxRepository.save(buildBookingConfirmedOutbox(BookingConfirmedPayload.from(paymentId, attempt, reservations, confirmed)));
		return PaymentConfirmResult.from(payment);
	}

	private PaymentOutbox buildBookingConfirmedOutbox(BookingConfirmedPayload payload) {
		String dedupKey = "payment:%d:booking-confirmed".formatted(payload.paymentId());
		try {
			String payloadJson = objectMapper.writeValueAsString(payload);
			return PaymentOutbox.forBookingConfirmed(payload.paymentId(), dedupKey, payloadJson);
		} catch (JacksonException e) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_SERIALIZATION_FAILED);
		}
	}
}
