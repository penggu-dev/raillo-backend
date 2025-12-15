package com.sudo.raillo.order.application;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final MemberRepository memberRepository;

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
	 * 주문 생성
	 * @param memberNo 회원 번호
	 * @param pendingBookings 주문할 PendingBooking 리스트
	 */
	public void createOrder(String memberNo, List<PendingBooking> pendingBookings) {
		validatePendingBookingsNotEmpty(pendingBookings);
		Member member = getMember(memberNo);
		BigDecimal totalAmount = calculateTotalAmount(pendingBookings);
		Order order = Order.create(member, totalAmount);
		orderRepository.save(order);

		pendingBookings.forEach(pendingBooking -> createOrderBooking(order, pendingBooking));
		log.info("[주문 생성] orderId={}, memberNo={}, totalAmount={}", order.getId(), memberNo, totalAmount);
	}

	private void createOrderBooking(Order order, PendingBooking pendingBooking) {
		TrainSchedule trainSchedule = getTrainSchedule(pendingBooking.getTrainScheduleId());
		ScheduleStop departureStop = getScheduleStop(pendingBooking.getDepartureStationId());
		ScheduleStop arrivalStop = getScheduleStop(pendingBooking.getArrivalStationId());

		OrderBooking orderBooking = OrderBooking.create(
			order,
			trainSchedule,
			departureStop,
			arrivalStop,
			pendingBooking.getTotalFare()
		);
		orderBookingRepository.save(orderBooking);

		pendingBooking.getPendingSeatBookings()
			.forEach(seatBooking -> createOrderSeatBooking(orderBooking, seatBooking));
	}

	private void createOrderSeatBooking(OrderBooking orderBooking, PendingSeatBooking pendingSeatBooking) {
		OrderSeatBooking orderSeatBooking = OrderSeatBooking.create(
			orderBooking,
			pendingSeatBooking.seatId(),
			pendingSeatBooking.passengerType()
		);
		orderSeatBookingRepository.save(orderSeatBooking);
	}

	private void validatePendingBookingsNotEmpty(List<PendingBooking> pendingBookings) {
		if (pendingBookings == null || pendingBookings.isEmpty()) {
			log.error("[주문 검증 실패] 빈 예약 리스트로 주문 시도");
			throw new BusinessException(OrderError.EMPTY_PENDING_BOOKINGS);
		}
	}

	private BigDecimal calculateTotalAmount(List<PendingBooking> pendingBookings) {
		return pendingBookings.stream()
			.map(PendingBooking::getTotalFare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_DETAIL_NOT_FOUND));
	}

	private ScheduleStop getScheduleStop(Long scheduleStopId) {
		return scheduleStopRepository.findById(scheduleStopId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}
}
