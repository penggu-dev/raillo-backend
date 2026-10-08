package com.sudo.raillo.payment.application;

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
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
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
	private final AttemptFailureMarker attemptFailureMarker;
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
		validateReservationsAlive(order, memberNo);
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

		return PaymentApprovalStart.started(payment.getId(), registered.attemptDbId());
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
			case REVIEW_REQUIRED -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_REVIEW_REQUIRED);
			case IN_PROGRESS -> recoverInProgressAttempt(existing, payment, ctx);
			// 호출이 나가지 않은 것이 확정이라 중복 승인 위험이 없다. 같은 attempt를 다시 열어 처음부터 시도한다.
			case NOT_SENT -> reopenNotSentAttempt(existing, payment);
		};
	}

	/**
	 * 전송되지 않은 attempt를 다시 열어 승인 시도를 이어가게 한다.
	 *
	 * <p>게이트웨이에 요청이 도달하지 않은 것이 확정이므로 조회로 대조할 것이 없다. 같은 attemptId를 그대로
	 * 쓰면 되므로 새 행을 만들지 않는다.</p>
	 */
	private PaymentApprovalStart reopenNotSentAttempt(PaymentAttempt existing, Payment payment) {
		log.info("[결제 재요청 - NOT_SENT attempt 재개] attemptId={}, paymentId={}",
			existing.getAttemptId(), payment.getId());
		paymentAttemptManager.reopenInNewTransaction(payment.getId(), existing.getId());
		return PaymentApprovalStart.started(payment.getId(), existing.getId());
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
			return handleQueryFailure(existing, payment, gatewayFailure);
		}
		GatewayPaymentStatus status = query.status();
		log.info("[결제 재요청 - 게이트웨이 상태 조회] paymentKey={}, status={}", paymentKey, status);

		return switch (status) {
			case DONE -> resolveGatewayDone(existing, payment, ctx, query);
			case ABORTED, EXPIRED, CANCELED, PARTIAL_CANCELED -> {
				// Toss 상 확정 실패 - attempt를 FAILED로 마킹하고 사용자에게 안내한다.
				log.warn("[결제 재요청 - 게이트웨이가 확정 실패로 응답] status={}, paymentKey={}", status, paymentKey);
				throw failAndReject(existing, payment,
					"GATEWAY_" + status.name(), "게이트웨이가 확정 실패로 응답했습니다.");
			}
			case READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, UNKNOWN -> {
				// 아직 처리 중이거나 상태 불명 - 로컬 IN_PROGRESS를 유지하고 사용자에게 재시도를 안내한다.
				log.info("[결제 재요청 - 게이트웨이가 아직 처리 중] status={}, paymentKey={}", status, paymentKey);
				throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
			}
		};
	}

	/** 게이트웨이가 이미 승인 완료로 응답한 경우. 로컬 DB는 TX B에서 정정한다. */
	private PaymentApprovalStart resolveGatewayDone(
		PaymentAttempt existing, Payment payment, ApprovalContext ctx, GatewayQueryResult query
	) {
		paymentValidator.validateGatewayResponseMatchesRequest(query.confirmResult(), ctx.command());
		try {
			validateReservationsAlive(ctx.order(), ctx.memberNo());
		} catch (BusinessException reservationCheckFailed) {
			return resolveWhenReservationsGone(existing, payment, reservationCheckFailed);
		}
		return PaymentApprovalStart.recovered(payment.getId(), existing.getId(), query.confirmResult());
	}

	/**
	 * 게이트웨이는 승인 완료인데 예약이 남아 있지 않은 경우를 attempt 상태로 다시 판단한다.
	 *
	 * <p>이 조회와 검증 사이 원 요청이 TX B를 커밋하고 Outbox Worker가 R→B(예약→예매 점유 전환)까지 끝냈다면
	 * 예약이 이미 정리된 것이 정상이다. 그래서 예약 검증 실패를 곧바로 사용자에게 돌려주지 않고 attempt를 다시 읽는다.
	 *
	 * <p>분기를 exhaustive switch로 둔다. 새 {@link PaymentAttemptStatus}가 생기면 컴파일이 멈춰 여기서
	 * 무엇을 할지 정하게 만든다. if 사슬이면 새 상태가 조용히 "원래 예외를 그대로" 경로로 떨어진다.</p>
	 */
	private PaymentApprovalStart resolveWhenReservationsGone(
		PaymentAttempt existing, Payment payment, BusinessException reservationCheckFailed
	) {
		PaymentAttempt refreshed = paymentAttemptRepository.findById(existing.getId())
			.orElseThrow(() -> reservationCheckFailed);

		return switch (refreshed.getStatus()) {
			case SUCCEEDED -> {
				log.info("[결제 재요청 - 조회 중 원 요청이 먼저 확정] attemptId={}, paymentId={}",
					existing.getAttemptId(), payment.getId());
				yield PaymentApprovalStart.alreadyConfirmed(paymentReader.getConfirmResult(payment.getId()));
			}
			// 조회 사이 다른 절차가 attempt를 수동 확인 대상으로 바꿨다면 예약 만료가 아니라 확인 필요로 안내한다.
			// 이미 카드가 승인된 상태이므로 재예약을 안내하는 reservationCheckFailed를 그대로 던지면 안 된다.
			case REVIEW_REQUIRED -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_REVIEW_REQUIRED);
			// 원래 예외(예약 만료 등)를 그대로 던진다.
			// NOT_SENT는 승인 호출이 나가지 않은 것이라 카드가 승인되지 않았다. 원래 예외를 그대로 던진다.
			case IN_PROGRESS, FAILED, NOT_SENT -> throw reservationCheckFailed;
		};
	}

	/**
	 * 게이트웨이 조회 자체가 실패한 경우의 처리. 조회 API의 4xx는 승인의 4xx와 의미가 달라, "결제 없음"이 확정된 응답만 attempt를 FAILED로 마킹한다.
	 * 그 외(인증 오류·rate limit·게이트웨이 내부 오류)는 승인 여부 판단 불가라 로컬 상태를 유지하고 재시도를 안내하며, Recovery Worker(#270)가 이어서 대사한다.
	 */
	private PaymentApprovalStart handleQueryFailure(PaymentAttempt existing, Payment payment, PaymentGatewayException failure) {
		if (failure.isResourceNotFound()) {
			log.warn("[결제 재요청 - 게이트웨이가 paymentKey를 찾지 못함] httpStatus={}, errorCode={}, message={}",
				failure.getHttpStatus(), failure.getErrorCode(), failure.getMessage());
			throw failAndReject(existing, payment,
				"GATEWAY_" + failure.getErrorCode(), failure.getMessage());
		}
		log.warn("[결제 재요청 - 게이트웨이 조회 실패, IN_PROGRESS 유지 후 Recovery 대기] httpStatus={}, errorCode={}",
			failure.getHttpStatus(), failure.getErrorCode());
		throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
	}

	/**
	 * attempt를 FAILED로 마킹하고 사용자에게 돌려줄 예외를 만든다.
	 *
	 * <p>마킹이 실패해도 여기서 만든 {@code PAYMENT_ATTEMPT_ALREADY_FAILED}를 가리면 안 되므로 삼키는 경로로 부른다.</p>
	 */
	private BusinessException failAndReject(PaymentAttempt existing, Payment payment, String code, String message) {
		attemptFailureMarker.markFailedQuietly(payment.getId(), existing.getId(), new AttemptError(code, message));
		return new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
	}

	private void validateReservationsAlive(Order order, String memberNo) {
		List<String> reservationIds = orderReader.getReservationIds(order);
		if (reservationIds.isEmpty()) {
			log.error("[Reservation 검증 실패] reservationIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.RESERVATION_IDS_REQUIRED);
		}
		reservationReader.getReservations(reservationIds, memberNo);
	}

	/**
	 * 승인 시작 흐름 내부에서 재요청·회복 처리 시 함께 전달되는 요청 컨텍스트.
	 * 파라미터 개수를 줄이기 위한 내부 캐리어이며 클래스 밖으로 노출하지 않는다.
	 */
	private record ApprovalContext(PaymentConfirmCommand command, String memberNo, Order order) {}
}
