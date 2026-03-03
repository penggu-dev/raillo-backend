package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.payment.application.validator.PaymentValidator;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.type.PaymentMethod;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.exception.TossPaymentException;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.train.application.service.TrainScheduleService;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;
import com.sudo.raillo.train.domain.ScheduleStop;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentFacade {

	private final PaymentService paymentService;
	private final OrderService orderService;
	private final MemberService memberService;
	private final PendingBookingService pendingBookingService;
	private final SeatHoldService seatHoldService;
	private final BookingService bookingService;
	private final TrainScheduleService trainScheduleService;
	private final TrainSeatQueryService trainSeatQueryService;
	private final TossPaymentClient tossPaymentClient;
	private final PaymentValidator paymentValidator;
	private final BookingValidator bookingValidator;

	/**
	 * 결제 준비 처리
	 *
	 * <p>1. PendingBooking 조회 (모든 Id 존재 확인, 소유자 검증)
	 * <p>2. Order 생성 (PENDING) - OrderBooking, OrderSeatBooking 함께 생성
	 * <p>3. Payment 생성 (PENDING)
	 * <p>4. orderId, amount 응답
	 */
	public PaymentPrepareResponse preparePayment(PaymentPrepareRequest request, String memberNo) {
		List<PendingBooking> pendingBookings = pendingBookingService.getPendingBookings(request.pendingBookingIds(), memberNo);

		bookingValidator.validateSeatConflicts(pendingBookings);

		Member member = memberService.getMemberByMemberNo(memberNo);
		Order order = orderService.createOrder(memberNo, pendingBookings);
		Payment payment = paymentService.createPayment(member, order);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, pendingBookingCount={}",
			order.getOrderCode(), payment.getId(), order.getTotalAmount(), pendingBookings.size());

		return new PaymentPrepareResponse(order.getOrderCode(), order.getTotalAmount());
	}

	/**
	 * 결제 승인 처리
	 *
	 * <p>1. 데이터 조회 및 검증 (Order, PendingBooking, Member, Payment)
	 * <p>2. 소유자 검증, 금액 3중 검증, 중복 결제 방지
	 * <p>3. PaymentKey 저장 (REQUIRES_NEW - 토스 호출 전 무조건 커밋)
	 * <p>4. 토스페이먼츠 결제 승인 API 호출 (실패 시 실패 정보 저장 후 예외 전파)
	 * <p>5. 토스 응답 검증 → Order/Payment 상태 변경 → Booking 생성 → 좌석 확정
	 */
	public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request, String memberNo) {
		log.info("[결제 승인 시작] orderId={}, paymentKey={}, amount={}",
			request.orderId(), request.paymentKey(), request.amount());

		// 1. 데이터 조회 및 PendingBooking 유효성 검증 (TTL 만료 여부)
		Order order = orderService.getOrderByOrderCode(request.orderId());
		List<PendingBooking> pendingBookings = validateAndGetPendingBookings(order, memberNo);
		Member member = memberService.getMemberByMemberNo(memberNo);
		Payment payment = paymentService.getPaymentByOrder(order);

		// 2. 요청 전 검증 (소유자, 금액, 중복결제)
		orderService.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(request.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);

		// 3. PaymentKey 저장 (REQUIRES_NEW - 토스 호출 전 무조건 커밋)
		paymentService.updatePaymentKeyInNewTransaction(payment.getId(), request.paymentKey());
		// REQUIRES_NEW로 별도 커밋된 paymentKey를 바깥 트랜잭션의 엔티티에도 동기화
		// (미동기화 시 바깥 트랜잭션 커밋 때 Hibernate가 paymentKey=null로 덮어씀)
		payment.updatePaymentKey(request.paymentKey());

		// 4. 토스페이먼츠 결제 승인 API 호출 (실패 시 REQUIRES_NEW로 실패 정보 저장 후 예외 전파)
		TossPaymentConfirmResponse tossResponse;
		try {
			tossResponse = tossPaymentClient.confirmPayment(request);
		} catch (TossPaymentException e) {
			paymentService.failPaymentInNewTransaction(payment.getId(), e.getErrorCode(), e.getMessage());
			log.info("[토스 결제 승인 실패] orderCode={}, httpStatus={}, tossCode={}, tossMessage={}",
				request.orderId(), e.getHttpStatus(), e.getErrorCode(), e.getMessage());
			throw e;
		}

		// 5. 토스 응답 검증 및 결제 확정
		paymentValidator.validateTossResponseMatchesRequest(tossResponse, request);
		PaymentMethod paymentMethod = mapToPaymentMethod(tossResponse.method());

		order.completePayment();
		bookingService.createBookingFromOrder(order);
		payment.approve(paymentMethod);
		cleanupPendingBookings(pendingBookings);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), request.orderId());
		return PaymentConfirmResponse.from(payment);
	}

	/**
	 * PendingBooking 유효성 검증 및 조회
	 *
	 * <p>결제 승인 전에 모든 PendingBooking이 살아있는지(TTL 만료되지 않았는지) 확인합니다.
	 * 하나라도 만료되었으면 결제를 진행할 수 없습니다.</p>
	 *
	 * @throws BusinessException PendingBooking이 없거나 일부가 만료된 경우
	 */
	private List<PendingBooking> validateAndGetPendingBookings(Order order, String memberNo) {
		List<String> pendingBookingIds = orderService.getPendingBookingIds(order);
		if (pendingBookingIds.isEmpty()) {
			log.error("[PendingBooking 검증 실패] pendingBookingIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.PENDING_BOOKING_IDS_REQUIRED);
		}

		// 모든 PendingBooking이 존재하는지 검증 (getPendingBookings 내부에서 검증)
		return pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);
	}

	/**
	 * PendingBooking 삭제 및 Hold 해제
	 * <p>예매가 완료된 PendingBooking에 대해 삭제 및 좌석 Hold 해제를 수행합니다.</p>
	 */
	private void cleanupPendingBookings(List<PendingBooking> pendingBookings) {
		List<String> pendingBookingIds = pendingBookings.stream()
			.map(PendingBooking::getId)
			.toList();
		String memberNo = pendingBookings.get(0).getMemberNo();

		try {
			pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);
		} catch (Exception e) {
			log.error("[PendingBooking 삭제 실패] error={}", e.getMessage(), e);
		}

		List<Long> allStopIds = pendingBookings.stream()
			.flatMap(pendingBooking ->
				Stream.of(pendingBooking.getDepartureStopId(), pendingBooking.getArrivalStopId()))
			.toList();

		Map<Long, ScheduleStop> stopMap = trainScheduleService.getScheduleStops(allStopIds)
				.stream()
				.collect(Collectors.toMap(ScheduleStop::getId, Function.identity()));

		pendingBookings.forEach(pendingBooking -> {
			List<Long> seatIds = pendingBooking.getSeatIds();
			Long trainCarId = trainSeatQueryService.getTrainCarId(seatIds);
			ScheduleStop departureStop = stopMap.get(pendingBooking.getDepartureStopId());
			ScheduleStop arrivalStop = stopMap.get(pendingBooking.getArrivalStopId());

			seatHoldService.releaseSeats(
				pendingBooking.getId(),
				pendingBooking.getTrainScheduleId(),
				seatIds,
				trainCarId,
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder()
			);
		});

		log.info("[PendingBooking 삭제 및 Hold 해제 완료] pendingBookingCount={}", pendingBookings.size());
	}

	/**
	 * 토스 결제수단 문자열을 PaymentMethod enum으로 매핑
	 */
	private PaymentMethod mapToPaymentMethod(String tossMethod) {
		return switch (tossMethod) {
			case "카드" -> PaymentMethod.CREDIT_CARD;
			case "가상계좌" -> PaymentMethod.VIRTUAL_ACCOUNT;
			case "계좌이체" -> PaymentMethod.TRANSFER;
			case "휴대폰" -> PaymentMethod.MOBILE_PHONE;
			case "간편결제" -> PaymentMethod.EASY_PAY;
			case "문화상품권", "도서문화상품권", "게임문화상품권" -> PaymentMethod.GIFT_CERTIFICATE;
			default -> {
				log.warn("알 수 없는 결제수단: {}", tossMethod);
				throw new BusinessException(PaymentError.INVALID_PAYMENT_METHOD, "지원하지 않는 결제 수단입니다: " + tossMethod);
			}
		};
	}
}
