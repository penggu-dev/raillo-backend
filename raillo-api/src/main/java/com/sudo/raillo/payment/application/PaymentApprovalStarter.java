package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.exception.PaymentGatewayException;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayPaymentStatus;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayQueryResult;
import com.sudo.raillo.payment.application.result.PaymentAttemptStartResult;
import com.sudo.raillo.payment.application.required.ReservationReader;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 승인 시작 단계. Toss 호출에 필요한 조회·검증을 마치고 {@link PaymentAttemptManager}로 TX A를 커밋한다.
 *
 * <p>자체 트랜잭션을 열지 않으며 커밋 지점은 TX A 하나뿐이다. 각 조회는 해당 컴포넌트의 짧은 트랜잭션에서 수행되고 Reservation(Redis) 조회도 DB 트랜잭션 밖에 있다.
 *
 * <p>같은 attemptId 재요청은 Toss 호출 없이 이전 결과를 반환하거나 예외를 던진다. IN_PROGRESS 재요청(사용자가 결제창에서 다시 시도한 경우)은 게이트웨이 조회 API로 실제 상태를 확인해 로컬을 정정한다.
 *
 * <p><b>3층 재검증 구조 중 첫 층(pre-check).</b> 잠금 없이 빠르게 실패시켜 Toss 호출까지 가지 않도록 한다. `validateApprovable`·`validateDuplicatePayment`·`validateApprovalAttempt`는 이후 TX A({@link PaymentAttemptManager})와 TX B({@link PaymentApprovalFinalizer})에서 동시성 방어 목적으로 다시 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApprovalStarter {

	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentReader paymentReader;
	private final PaymentValidator paymentValidator;
	private final OrderReader orderReader;
	private final MemberFinder memberFinder;
	private final ReservationReader reservationReader;
	private final PaymentGateway paymentGateway;

	public PaymentApprovalStart start(PaymentConfirmCommand command, String memberNo) {
		String attemptId = command.attemptId();
		paymentValidator.validateAttemptId(attemptId);
		log.info("[결제 승인 시작] orderId={}, paymentKey={}, amount={}, attemptId={}",
			command.orderId(), command.paymentKey(), command.amount(), attemptId);

		Order order = orderReader.getOrderByOrderCode(command.orderId());
		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Payment payment = paymentReader.getPaymentByOrder(order);

		// 소유권·금액은 재요청·신규 승인 모두에 필요하고 남의 결제 상태 probing도 막아야 하므로 attempt 분기 앞에서 자른다.
		orderReader.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(command.amount(), order.getTotalAmount(), payment.getAmount());

		ApprovalContext ctx = new ApprovalContext(command, memberNo, order);

		// 재요청/신규 승인의 분수령. 같은 attemptId 재요청은 상태에 따라 이전 결과를 반환하거나 예외로 조기 종료한다.
		// Reservation 조회를 뒤로 미뤄야 SUCCEEDED 재요청도 정상 응답한다(승인 후 Reservation은 이미 정리됐을 수 있음).
		Optional<PaymentAttempt> existingAttempt = paymentAttemptRepository.findByAttemptId(attemptId);
		if (existingAttempt.isPresent()) {
			return handleExistingAttempt(existingAttempt.get(), payment, ctx);
		}

		// 신규 승인 경로에만 필요한 검증. 위 attempt 조회가 attemptId 축이라면 아래 둘은 Payment/Order 상태 축이다.
		// - validateApprovable: 이 Payment가 PENDING인가.
		// - validateDuplicatePayment: 같은 Order에 이미 PAID된 Payment가 있는가.
		//   Order 하나에 Payment가 여러 개 붙는 경로는 정상 흐름이 아니지만 예약 소유권 강제(#280)까지의 방어층으로 남겨둔다.
		paymentValidator.validateApprovable(payment);
		// TODO(#272): 이 조회 직전 다른 요청이 승인을 확정해 Reservation이 정리된 경로도 통합 테스트로 검증한다.
		List<Reservation> reservations = getReservations(order, memberNo);
		paymentValidator.validateDuplicatePayment(order);

		// UNIQUE(attempt_id)가 최종 방어층으로, pre-check와 TX A 락이 놓친 경합 케이스를 여기서 잡는다.
		PaymentAttemptStartResult registered;
		try {
			registered = paymentAttemptManager.startApprovalInNewTransaction(payment.getId(), attemptId, command.paymentKey());
		} catch (DataIntegrityViolationException e) {
			// TX A가 롤백돼 attempt 객체를 전달받지 못하므로 재조회한다. UNIQUE 충돌이면 재요청 흐름으로, 그 외 무결성 오류는 그대로 전파한다.
			PaymentAttempt conflicting = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow(() -> e);
			return handleExistingAttempt(conflicting, payment, ctx);
		}

		if (!registered.created()) {
			// TX A가 성공적으로 커밋했으므로 결과에 담긴 attempt를 재조회 없이 재사용한다.
			return handleExistingAttempt(registered.attempt(), payment, ctx);
		}

		return PaymentApprovalStart.started(payment.getId(), registered.attemptDbId(), reservations);
	}

	private PaymentApprovalStart handleExistingAttempt(
		PaymentAttempt existing, Payment payment, ApprovalContext ctx
	) {
		paymentValidator.validateApprovalAttempt(existing, payment.getId(), ctx.command().paymentKey());
		return switch (existing.getStatus()) {
			case SUCCEEDED -> {
				log.info("[결제 재요청 - SUCCEEDED attempt 재사용] attemptId={}, paymentId={}", existing.getAttemptId(), payment.getId());
				// 별도 읽기 트랜잭션에서 최신 커밋 결과를 조회한다.
				yield PaymentApprovalStart.alreadyConfirmed(paymentReader.getConfirmResult(payment.getId()));
			}
			case FAILED -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
			case IN_PROGRESS -> recoverInProgressAttempt(existing, payment, ctx);
		};
	}

	/** IN_PROGRESS attempt 발견 시(사용자 재시도 또는 동시 요청 경합) 게이트웨이 상태를 조회해 로컬을 정정한다. */
	private PaymentApprovalStart recoverInProgressAttempt(
		PaymentAttempt existing, Payment payment, ApprovalContext ctx
	) {
		log.info("[결제 재요청 - IN_PROGRESS attempt 상태 재조회 시작] attemptId={}, paymentId={}", existing.getAttemptId(), payment.getId());

		String paymentKey = ctx.command().paymentKey();
		GatewayQueryResult query;
		try {
			query = paymentGateway.query(paymentKey);
		} catch (PaymentGatewayException gatewayFailure) {
			return handleQueryFailure(existing, gatewayFailure);
		}
		GatewayPaymentStatus status = query.status();
		log.info("[결제 재요청 - 게이트웨이 상태 조회] paymentKey={}, status={}", paymentKey, status);

		return switch (status) {
			case DONE -> {
				// Toss 상 이미 승인 완료 - 로컬 DB를 TX B에서 정정한다.
				paymentValidator.validateGatewayResponseMatchesRequest(query.confirmResult(), ctx.command());
				List<Reservation> reservations = getReservations(ctx.order(), ctx.memberNo());
				yield PaymentApprovalStart.recovered(
					payment.getId(), existing.getId(), reservations, query.confirmResult()
				);
			}
			case ABORTED, EXPIRED, CANCELED, PARTIAL_CANCELED -> {
				// Toss 상 확정 실패 - attempt를 FAILED로 마킹하고 사용자에게 안내한다.
				log.warn("[결제 재요청 - 게이트웨이가 확정 실패로 응답] status={}, paymentKey={}", status, paymentKey);
				paymentAttemptManager.markFailedInNewTransaction(
					existing.getId(), "GATEWAY_" + status.name(), "게이트웨이가 확정 실패로 응답했습니다."
				);
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
			}
			case READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, UNKNOWN -> {
				// 아직 처리 중이거나 상태 불명 - 로컬 IN_PROGRESS를 유지하고 사용자에게 재시도를 안내한다.
				log.info("[결제 재요청 - 게이트웨이가 아직 처리 중] status={}, paymentKey={}", status, paymentKey);
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
			}
		};
	}

	/**
	 * 게이트웨이 조회 자체가 실패한 경우의 처리. 조회 API의 4xx는 승인의 4xx와 의미가 달라, "결제 없음"이 확정된 응답만 attempt를 FAILED로 마킹한다.
	 * 그 외(인증 오류·rate limit·게이트웨이 내부 오류)는 승인 여부 판단 불가라 로컬 상태를 유지하고 재시도를 안내하며, Recovery Worker(#270)가 이어서 대사한다.
	 */
	private PaymentApprovalStart handleQueryFailure(PaymentAttempt existing, PaymentGatewayException failure) {
		if (failure.isResourceNotFound()) {
			log.warn("[결제 재요청 - 게이트웨이가 paymentKey를 찾지 못함] httpStatus={}, errorCode={}, message={}",
				failure.getHttpStatus(), failure.getErrorCode(), failure.getMessage());
			paymentAttemptManager.markFailedInNewTransaction(
				existing.getId(), "GATEWAY_" + failure.getErrorCode(), failure.getMessage()
			);
			throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
		}
		log.warn("[결제 재요청 - 게이트웨이 조회 실패, IN_PROGRESS 유지 후 Recovery 대기] httpStatus={}, errorCode={}",
			failure.getHttpStatus(), failure.getErrorCode());
		throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
	}

	private List<Reservation> getReservations(Order order, String memberNo) {
		List<String> reservationIds = orderReader.getReservationIds(order);
		if (reservationIds.isEmpty()) {
			log.error("[Reservation 검증 실패] reservationIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.RESERVATION_IDS_REQUIRED);
		}
		return reservationReader.getReservations(reservationIds, memberNo);
	}

	/**
	 * 승인 시작 흐름 내부에서 재요청·회복 처리 시 함께 전달되는 요청 컨텍스트.
	 * 파라미터 개수를 줄이기 위한 내부 캐리어이며 클래스 밖으로 노출하지 않는다.
	 */
	private record ApprovalContext(PaymentConfirmCommand command, String memberNo, Order order) {}
}
