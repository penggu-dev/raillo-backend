package com.sudo.raillo.payment.application;

import com.sudo.raillo.payment.application.command.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.result.PaymentPrepareResult;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderRegister;
import com.sudo.raillo.payment.application.required.ReservationReader;
import com.sudo.raillo.payment.application.required.SeatConflictValidator;
import com.sudo.raillo.payment.domain.Payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 준비 유스케이스.
 *
 * <p>1. Reservation 조회 및 소유자 검증
 * <p>2. 좌석 충돌 검증
 * <p>3. Order 생성 (PENDING)
 * <p>4. Payment 생성 (PENDING)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentPrepareService implements PaymentPreparer {

	private final PaymentModifier paymentModifier;
	private final OrderRegister orderRegister;
	private final MemberFinder memberFinder;
	private final ReservationReader reservationReader;
	private final SeatConflictValidator seatConflictValidator;

	@Override
	public PaymentPrepareResult prepare(PaymentPrepareCommand command, String memberNo) {
		List<Reservation> reservations = reservationReader.getReservations(command.reservationIds(), memberNo);
		seatConflictValidator.validateSeatConflicts(reservations);

		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Order order = orderRegister.createOrder(memberNo, reservations);
		Payment payment = paymentModifier.createPayment(member, order);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, reservationCount={}",
			order.getOrderCode(), payment.getId(), order.getTotalAmount(), reservations.size());

		return PaymentPrepareResult.from(order);
	}
}
