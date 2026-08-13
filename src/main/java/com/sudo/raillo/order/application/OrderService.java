package com.sudo.raillo.order.application;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.infrastructure.ReservationSeatRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.application.validator.OrderValidator;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final MemberRepository memberRepository;
	private final OrderValidator orderValidator;

	/**
	 * OrderCode로 Order 조회
	 */
	@Transactional(readOnly = true)
	public Order getOrderByOrderCode(String orderCode) {
		return orderRepository.findByOrderCode(orderCode)
			.orElseThrow(() -> new BusinessException(OrderError.ORDER_NOT_FOUND));
	}

	/**
	 * 주문 소유자 검증
	 */
	@Transactional(readOnly = true)
	public void validateOrderOwner(Order order, Member member) {
		if (!order.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Order의 소유자가 아님: orderCode={}, requestMemberId={}, orderMemberId={}",
				order.getOrderCode(), member.getId(), order.getMember().getId());
			throw new BusinessException(OrderError.ORDER_ACCESS_DENIED);
		}
	}

	/**
	 * Order에 연결된 예약 코드 목록 조회
	 */
	@Transactional(readOnly = true)
	public List<String> getReservationCodes(Order order) {
		return orderBookingRepository.findByOrderIdWithReservation(order.getId()).stream()
			.map(orderBooking -> orderBooking.getReservation().getReservationCode())
			.toList();
	}

	/**
	 * 주문 생성
	 *
	 * <p>운임은 예약 시점에 계산되어 {@link ReservationSeat}에 저장되어 있으므로 재계산하지 않는다.
	 * 예약 화면에 표시된 금액과 결제 금액이 항상 일치한다.</p>
	 *
	 * @param memberNo 회원 번호
	 * @param reservations 주문할 예약 목록
	 * @return 생성된 Order
	 */
	public Order createOrder(String memberNo, List<Reservation> reservations) {
		orderValidator.validateReservationsNotEmpty(reservations);

		Member member = getMember(memberNo);
		Map<Long, List<ReservationSeat>> seatsByReservationId = getReservationSeats(reservations);

		BigDecimal totalAmount = reservations.stream()
			.map(Reservation::getTotalFare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		Order order = Order.create(member, totalAmount);
		orderRepository.save(order);

		reservations.forEach(reservation -> createOrderBooking(
			order, reservation, seatsByReservationId.getOrDefault(reservation.getId(), List.of())));

		log.info("[주문 생성] orderId={}, memberNo={}, totalAmount={}", order.getId(), memberNo, totalAmount);
		return order;
	}

	private void createOrderBooking(Order order, Reservation reservation, List<ReservationSeat> reservationSeats) {
		OrderBooking orderBooking = OrderBooking.create(
			reservation,
			order,
			reservation.getTrainSchedule(),
			reservation.getDepartureStop(),
			reservation.getArrivalStop(),
			reservation.getTotalFare()
		);
		orderBookingRepository.save(orderBooking);

		reservationSeats.stream()
			.map(reservationSeat -> OrderSeatBooking.create(
				orderBooking,
				reservationSeat.getSeat().getId(),
				reservationSeat.getPassengerType(),
				reservationSeat.getFare()
			))
			.forEach(orderSeatBookingRepository::save);
	}

	private Map<Long, List<ReservationSeat>> getReservationSeats(List<Reservation> reservations) {
		List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();

		return reservationSeatRepository.findAllByReservationIdInWithSeat(reservationIds).stream()
			.collect(Collectors.groupingBy(reservationSeat -> reservationSeat.getReservation().getId()));
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}
}
